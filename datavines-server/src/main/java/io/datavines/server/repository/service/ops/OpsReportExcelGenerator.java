/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.datavines.server.repository.service.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import io.datavines.common.param.ConnectorResponse;
import io.datavines.common.param.ExecuteRequestParam;
import io.datavines.common.utils.JSONUtils;
import io.datavines.connector.api.ConnectorFactory;
import io.datavines.server.api.dto.bo.ops.OpsReportConfig;
import io.datavines.server.api.dto.vo.OpsReportQualityAggVO;
import io.datavines.server.repository.entity.DataSource;
import io.datavines.server.repository.entity.OpsReportProfile;
import io.datavines.server.repository.mapper.OpsReportRunMapper;
import io.datavines.server.repository.service.DataSourceService;
import io.datavines.server.repository.service.JobExecutionErrorDataService;
import io.datavines.spi.PluginDiscovery;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Component
public class OpsReportExcelGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter CN_DAY = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private OpsReportRunMapper opsReportRunMapper;

    @Autowired
    private JobExecutionErrorDataService jobExecutionErrorDataService;

    @Value("${datavines.ops-report.dir:/data/datavines/ops-reports}")
    private String reportDir;

    private volatile Map<String, RuleMeta> ruleByName;

    public Path ensureRunDir(Long workspaceId, Long runId) throws IOException {
        Path dir = Paths.get(reportDir, String.valueOf(workspaceId), String.valueOf(runId));
        Files.createDirectories(dir);
        return dir;
    }

    public GenerateResult generate(OpsReportProfile profile, LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                   LocalDate statDate, Path runDir) throws Exception {
        List<Long> dsIds = JSONUtils.parseObject(profile.getDatasourceIds(), new TypeReference<List<Long>>() {});
        if (CollectionUtils.isEmpty(dsIds)) {
            throw new IllegalArgumentException("datasourceIds empty");
        }
        OpsReportConfig config = JSONUtils.parseObject(profile.getConfigJson(), OpsReportConfig.class);
        if (config == null || CollectionUtils.isEmpty(config.getTables())) {
            throw new IllegalArgumentException("config_json.tables required");
        }
        int sampleLimit = config.getErrorSampleLimit() == null ? 50 : config.getErrorSampleLimit();
        int detailMaxRows = config.getErrorDetailMaxRows() == null || config.getErrorDetailMaxRows() <= 0
                ? 100000 : config.getErrorDetailMaxRows();
        List<String> timeFields = CollectionUtils.isEmpty(config.getTimeFields())
                ? Arrays.asList("create_time", "update_time")
                : config.getTimeFields();
        String businessTag = resolveBusinessTag(profile, config);

        Map<Long, DataSource> dsMap = new LinkedHashMap<>();
        for (Long id : dsIds) {
            DataSource ds = dataSourceService.getDataSourceById(id);
            if (ds != null) {
                dsMap.put(id, ds);
            }
        }

        Map<String, SubmitStat> submitStats = new LinkedHashMap<>();
        for (OpsReportConfig.TableMapping tm : config.getTables()) {
            if (tm.getByDatasource() == null) {
                continue;
            }
            for (Map.Entry<String, String> e : tm.getByDatasource().entrySet()) {
                Long dsId = Long.parseLong(e.getKey());
                if (!dsMap.containsKey(dsId)) {
                    continue;
                }
                String table = e.getValue();
                SubmitStat st = querySubmitStat(dsMap.get(dsId), table, timeFields);
                st.cnName = tm.getCnName();
                st.bizName = StringUtils.defaultIfBlank(businessTag,
                        StringUtils.defaultIfBlank(tm.getBizName(), ""));
                st.tableEn = table;
                st.datasourceId = dsId;
                st.cityName = dsMap.get(dsId).getName();
                submitStats.put(dsId + "|" + table, st);
            }
        }

        // as-of rangeEnd: latest execution per job (pass + fail), filtered by business tag
        List<OpsReportQualityAggVO> latest =
                opsReportRunMapper.listFailedQualityResults(dsIds, rangeStart, rangeEnd, businessTag);
        List<QualityRow> qualityRows = new ArrayList<>();
        for (OpsReportQualityAggVO f : latest) {
            QualityRow qr = new QualityRow();
            qr.datasourceId = f.getDatasourceId();
            qr.cityName = dsMap.containsKey(f.getDatasourceId())
                    ? dsMap.get(f.getDatasourceId()).getName() : String.valueOf(f.getDatasourceId());
            qr.tableEn = f.getTableName();
            qr.cnName = resolveCnName(config, f.getDatasourceId(), f.getTableName());
            qr.jobId = f.getJobId();
            qr.columnName = f.getColumnName();
            qr.metricName = f.getMetricName();
            String jobName = StringUtils.defaultIfBlank(f.getJobName(), f.getExecutionName());
            RuleMeta meta = matchRule(jobName);
            // Prefer dv_job.rule_id; then catalog meta; do not fall back to internal PK
            if (StringUtils.isNotBlank(f.getRuleId())) {
                qr.ruleId = f.getRuleId().trim();
            } else if (meta != null && StringUtils.isNotBlank(meta.ruleId)) {
                qr.ruleId = meta.ruleId;
            } else {
                qr.ruleId = "";
            }
            qr.cnField = meta == null ? StringUtils.defaultString(f.getColumnName()) : meta.cnField;
            qr.ruleText = meta == null
                    ? StringUtils.defaultIfBlank(jobName, f.getMetricName())
                    : meta.ruleName;
            qr.level = meta == null || StringUtils.isBlank(meta.level) ? "2" : meta.level;
            long problem = f.getActualValue() == null ? 0L : f.getActualValue().longValue();
            qr.problemCount = problem;
            SubmitStat ss = findSubmit(submitStats, f.getDatasourceId(), f.getTableName());
            qr.totalCount = ss == null ? 0L : ss.count;
            qr.passRate = qr.totalCount <= 0 ? null :
                    BigDecimal.ONE.subtract(BigDecimal.valueOf(problem)
                            .divide(BigDecimal.valueOf(qr.totalCount), 6, RoundingMode.HALF_UP));
            qr.jobExecutionId = f.getJobExecutionId();
            qr.errorDataStorageType = f.getErrorDataStorageType();
            qr.errorDataStorageParameter = f.getErrorDataStorageParameter();
            qr.errorDataFileName = f.getErrorDataFileName();
            qualityRows.add(qr);
        }

        String day = (statDate == null ? LocalDate.now() : statDate).format(DAY);
        Path ledger = runDir.resolve("统计报表总台账-" + day + ".xlsx");
        writeLedger(ledger, dsMap, config, submitStats, qualityRows, statDate);

        Path zip = runDir.resolve("下级部门整改清单-" + day + ".zip");
        boolean hasChecklist = writeChecklistsZip(zip, qualityRows, sampleLimit, detailMaxRows, statDate);

        GenerateResult result = new GenerateResult();
        result.ledgerPath = ledger.toAbsolutePath().toString();
        result.checklistZipPath = hasChecklist ? zip.toAbsolutePath().toString() : null;
        return result;
    }

    private String resolveBusinessTag(OpsReportProfile profile, OpsReportConfig config) {
        if (config != null && StringUtils.isNotEmpty(config.getBusinessTag())) {
            return config.getBusinessTag().trim();
        }
        if (profile != null && StringUtils.isNotEmpty(profile.getBusinessType())
                && !"DEFAULT".equalsIgnoreCase(profile.getBusinessType())) {
            return profile.getBusinessType().trim();
        }
        if (config != null && CollectionUtils.isNotEmpty(config.getTables())) {
            for (OpsReportConfig.TableMapping tm : config.getTables()) {
                if (tm != null && StringUtils.isNotEmpty(tm.getBizName())) {
                    return tm.getBizName().trim();
                }
            }
        }
        if (profile != null && StringUtils.isNotEmpty(profile.getBusinessType())) {
            return profile.getBusinessType().trim();
        }
        return null;
    }

    private RuleMeta matchRule(String jobName) {
        if (StringUtils.isBlank(jobName)) {
            return null;
        }
        Map<String, RuleMeta> map = loadRuleCatalog();
        RuleMeta exact = map.get(jobName.trim());
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, RuleMeta> e : map.entrySet()) {
            if (jobName.contains(e.getKey()) || e.getKey().contains(jobName)) {
                return e.getValue();
            }
        }
        return null;
    }

    private Map<String, RuleMeta> loadRuleCatalog() {
        if (ruleByName != null) {
            return ruleByName;
        }
        synchronized (this) {
            if (ruleByName != null) {
                return ruleByName;
            }
            Map<String, RuleMeta> map = new LinkedHashMap<>();
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("ops/rules_catalog.csv")) {
                if (in != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        boolean first = true;
                        while ((line = br.readLine()) != null) {
                            if (first) {
                                first = false;
                                continue;
                            }
                            String[] p = line.split(",", 4);
                            if (p.length < 4) {
                                continue;
                            }
                            RuleMeta m = new RuleMeta();
                            m.ruleId = p[0].trim();
                            m.ruleName = p[1].trim();
                            m.tableEn = p[2].trim();
                            m.tableCn = p[3].trim();
                            int idx = m.ruleName.indexOf('_');
                            m.cnField = idx > 0 ? m.ruleName.substring(0, idx) : m.ruleName;
                            m.level = "2";
                            map.put(m.ruleName, m);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("load rule catalog failed: {}", e.getMessage());
            }
            ruleByName = map;
            return ruleByName;
        }
    }

    private String resolveCnName(OpsReportConfig config, Long dsId, String tableEn) {
        if (StringUtils.isBlank(tableEn)) {
            return "";
        }
        for (OpsReportConfig.TableMapping tm : config.getTables()) {
            if (tm.getByDatasource() == null) {
                continue;
            }
            String t = tm.getByDatasource().get(String.valueOf(dsId));
            if (tableEn.equalsIgnoreCase(t)) {
                return tm.getCnName();
            }
        }
        Map<String, RuleMeta> catalog = loadRuleCatalog();
        for (RuleMeta m : catalog.values()) {
            if (tableEn.equalsIgnoreCase(m.tableEn)) {
                return m.tableCn;
            }
        }
        return tableEn;
    }

    private SubmitStat findSubmit(Map<String, SubmitStat> map, Long dsId, String tableEn) {
        if (dsId == null || tableEn == null) {
            return null;
        }
        return map.get(dsId + "|" + tableEn);
    }

    private SubmitStat querySubmitStat(DataSource ds, String table, List<String> timeFields) {
        SubmitStat st = new SubmitStat();
        st.count = 0L;
        try {
            String maxExpr = timeFields.stream()
                    .map(f -> "MAX(" + f + ")")
                    .collect(Collectors.joining(", "));
            String sql = "SELECT COUNT(*) AS cnt, " + maxExpr + " FROM " + table;
            ExecuteRequestParam param = new ExecuteRequestParam();
            param.setType(ds.getType());
            param.setDataSourceParam(ds.getParam());
            param.setScript(sql);
            ConnectorFactory factory = PluginDiscovery
                    .getMultiKeyPluginDiscovery(ConnectorFactory.class, ConnectorFactory::getPluginNames)
                    .getOrCreatePlugin(ds.getType());
            ConnectorResponse resp = factory.getExecutor().queryForList(param);
            if (resp != null && resp.getResult() instanceof io.datavines.common.entity.ListWithQueryColumn) {
                io.datavines.common.entity.ListWithQueryColumn list =
                        (io.datavines.common.entity.ListWithQueryColumn) resp.getResult();
                if (CollectionUtils.isNotEmpty(list.getResultList())) {
                    Map<String, Object> row = list.getResultList().get(0);
                    st.count = toLong(first(row, "cnt", "CNT", "count"));
                    LocalDateTime latest = null;
                    for (Map.Entry<String, Object> cell : row.entrySet()) {
                        if ("cnt".equalsIgnoreCase(cell.getKey()) || "count".equalsIgnoreCase(cell.getKey())) {
                            continue;
                        }
                        LocalDateTime t = toDateTime(cell.getValue());
                        if (t != null && (latest == null || t.isAfter(latest))) {
                            latest = t;
                        }
                    }
                    st.latest = latest;
                }
            }
        } catch (Exception e) {
            log.warn("query submit stat failed ds={} table={}: {}", ds.getId(), table, e.getMessage());
            st.error = e.getMessage();
        }
        return st;
    }

    private Object first(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            if (row.containsKey(k)) {
                return row.get(k);
            }
        }
        return row.isEmpty() ? null : row.values().iterator().next();
    }

    private long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0L;
        }
    }

    private LocalDateTime toDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime) {
            return (LocalDateTime) v;
        }
        if (v instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) v).toLocalDateTime();
        }
        if (v instanceof java.util.Date) {
            return new java.sql.Timestamp(((java.util.Date) v).getTime()).toLocalDateTime();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            if (s.length() >= 19) {
                return LocalDateTime.parse(s.substring(0, 19).replace(' ', 'T'));
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void writeLedger(Path path, Map<Long, DataSource> dsMap, OpsReportConfig config,
                             Map<String, SubmitStat> submitStats, List<QualityRow> qualityRows,
                             LocalDate statDate) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles styles = new Styles(wb);
            String dateCn = (statDate == null ? LocalDate.now() : statDate).format(CN_DAY);
            writeToc(wb, styles, dateCn);
            writePivot(wb, styles, dsMap, config, submitStats, qualityRows);
            writeSubmit(wb, styles, submitStats, statDate);
            writeCityQuality(wb, styles, qualityRows, dateCn);
            writeProvQuality(wb, styles, qualityRows, dateCn);
            try (OutputStream out = Files.newOutputStream(path)) {
                wb.write(out);
            }
        }
    }

    private void writeToc(Workbook wb, Styles styles, String dateCn) {
        Sheet toc = wb.createSheet("目录");
        setWidths(toc, 8, 28, 28, 36, 14);
        String[] headers = {"序号", "统计内容", "目录", "超链接", "统计日期"};
        writeHeaderRow(toc, 0, headers, styles.header);
        Object[][] rows = {
                {"1", "下级部门数据上报情况", "数据报送情况",
                        "HYPERLINK(\"#数据报送情况!A1\",\"数据报送情况\")", dateCn},
                {"2", "下级部门数据报送情况", "下级部门数据报送透视表",
                        "HYPERLINK(\"#下级部门数据报送透视表!A1\",\"下级部门数据报送透视表\")", ""},
                {"3", "下级部门数据质量统计情况", "下级部门数据质量统计情况",
                        "HYPERLINK(\"#下级部门数据质量统计情况!A1\",\"下级部门数据质量统计情况\")", ""},
                {"4", "省局报送数据质量统计情况", "省局报送数据质量统计情况",
                        "HYPERLINK(\"#省局报送数据质量统计情况!A1\",\"省局报送数据质量统计情况\")", ""},
        };
        for (int i = 0; i < rows.length; i++) {
            Row r = toc.createRow(i + 1);
            for (int c = 0; c < rows[i].length; c++) {
                Cell cell = r.createCell(c);
                String v = String.valueOf(rows[i][c]);
                if (c == 3 && v.startsWith("HYPERLINK")) {
                    cell.setCellFormula(v);
                } else {
                    cell.setCellValue(v);
                }
                cell.setCellStyle(styles.body);
            }
        }
        toc.getRow(1).getCell(4).setCellStyle(styles.headerRed);
    }

    private void writePivot(Workbook wb, Styles styles, Map<Long, DataSource> dsMap,
                            OpsReportConfig config, Map<String, SubmitStat> submitStats,
                            List<QualityRow> qualityRows) {
        Sheet sheet = wb.createSheet("下级部门数据报送透视表");
        setWidths(sheet, 28, 18, 18, 18, 18, 18, 12, 12);
        List<DataSource> cities = new ArrayList<>(dsMap.values());
        List<OpsReportConfig.TableMapping> tables = config.getTables();

        writeHeaderRow(sheet, 0, new String[]{
                "下级部门名称", "当前已通过前置机报送", "申请使用上级系统报送", "申请使用前置机报送", "备注"
        }, styles.header);
        int r = 1;
        for (DataSource ds : cities) {
            Row row = sheet.createRow(r++);
            setCell(row, 0, ds.getName(), styles.body);
            setCell(row, 1, 1, styles.bodyCenter);
            setCell(row, 2, "", styles.body);
            setCell(row, 3, 1, styles.bodyCenter);
            setCell(row, 4, "", styles.body);
        }

        r += 1;
        int titleRow = r;
        Row t1 = sheet.createRow(r++);
        setCell(t1, 0, "下级部门名称", styles.header);
        setCell(t1, 1, "当前已通过前置库报送数据量", styles.header);
        sheet.addMergedRegion(new CellRangeAddress(titleRow, titleRow, 1, Math.max(1, tables.size())));
        applyBorder(sheet, new CellRangeAddress(titleRow, titleRow, 1, Math.max(1, tables.size())));

        int headerRow = r;
        Row h = sheet.createRow(r++);
        setCell(h, 0, "", styles.header);
        for (int i = 0; i < tables.size(); i++) {
            setCell(h, i + 1, tables.get(i).getCnName(), styles.header);
        }
        setCell(h, tables.size() + 1, "总计", styles.header);
        setCell(h, tables.size() + 2, "备注", styles.header);
        sheet.addMergedRegion(new CellRangeAddress(titleRow, headerRow, 0, 0));

        int dataStart = r;
        for (DataSource ds : cities) {
            Row row = sheet.createRow(r++);
            setCell(row, 0, ds.getName(), styles.body);
            for (int i = 0; i < tables.size(); i++) {
                OpsReportConfig.TableMapping tm = tables.get(i);
                String table = tm.getByDatasource() == null ? null : tm.getByDatasource().get(String.valueOf(ds.getId()));
                SubmitStat st = table == null ? null : submitStats.get(ds.getId() + "|" + table);
                setCell(row, i + 1, st == null ? 0 : st.count, styles.bodyCenter);
            }
            String colStart = col(1);
            String colEnd = col(tables.size());
            Cell sum = row.createCell(tables.size() + 1);
            sum.setCellFormula("SUM(" + colStart + r + ":" + colEnd + r + ")");
            sum.setCellStyle(styles.bodyCenter);
            setCell(row, tables.size() + 2, "", styles.body);
        }
        int dataEnd = r - 1;
        Row total = sheet.createRow(r++);
        setCell(total, 0, "总计", styles.header);
        for (int i = 0; i < tables.size(); i++) {
            Cell c = total.createCell(i + 1);
            c.setCellFormula("SUM(" + col(i + 1) + (dataStart + 1) + ":" + col(i + 1) + (dataEnd + 1) + ")");
            c.setCellStyle(styles.header);
        }
        Cell grand = total.createCell(tables.size() + 1);
        grand.setCellFormula("SUM(" + col(tables.size() + 1) + (dataStart + 1) + ":"
                + col(tables.size() + 1) + (dataEnd + 1) + ")");
        grand.setCellStyle(styles.header);

        // pass-rate block
        r += 1;
        int rateTitle = r;
        Row rt = sheet.createRow(r++);
        setCell(rt, 0, "下级部门名称", styles.header);
        setCell(rt, 1, "当前已报数据合格率（平均值）", styles.header);
        sheet.addMergedRegion(new CellRangeAddress(rateTitle, rateTitle, 1, Math.max(1, tables.size())));
        int rateHeader = r;
        Row rh = sheet.createRow(r++);
        setCell(rh, 0, "", styles.header);
        for (int i = 0; i < tables.size(); i++) {
            setCell(rh, i + 1, tables.get(i).getCnName(), styles.header);
        }
        setCell(rh, tables.size() + 1, "总计", styles.header);
        setCell(rh, tables.size() + 2, "备注", styles.header);
        sheet.addMergedRegion(new CellRangeAddress(rateTitle, rateHeader, 0, 0));

        Map<String, Double> rateMap = avgPassRateByCityTable(qualityRows);
        int rateStart = r;
        for (DataSource ds : cities) {
            Row row = sheet.createRow(r++);
            setCell(row, 0, ds.getName(), styles.body);
            for (int i = 0; i < tables.size(); i++) {
                OpsReportConfig.TableMapping tm = tables.get(i);
                String table = tm.getByDatasource() == null ? null : tm.getByDatasource().get(String.valueOf(ds.getId()));
                Double rate = table == null ? null : rateMap.get(ds.getId() + "|" + table);
                if (rate == null) {
                    setCell(row, i + 1, "", styles.bodyCenter);
                } else {
                    setCell(row, i + 1, rate, styles.pct);
                }
            }
            Cell avg = row.createCell(tables.size() + 1);
            avg.setCellFormula("IF(COUNTA(" + col(1) + r + ":" + col(tables.size()) + r + ")=0,\"\",AVERAGE("
                    + col(1) + r + ":" + col(tables.size()) + r + "))");
            avg.setCellStyle(styles.pct);
            setCell(row, tables.size() + 2, "", styles.body);
        }
        int rateEnd = r - 1;
        if (rateEnd >= rateStart) {
            Row rateTotal = sheet.createRow(r);
            setCell(rateTotal, 0, "总计", styles.header);
            for (int i = 0; i < tables.size() + 1; i++) {
                Cell c = rateTotal.createCell(i + 1);
                c.setCellFormula("IF(COUNTA(" + col(i + 1) + (rateStart + 1) + ":" + col(i + 1) + (rateEnd + 1)
                        + ")=0,\"\",AVERAGE(" + col(i + 1) + (rateStart + 1) + ":" + col(i + 1) + (rateEnd + 1) + "))");
                c.setCellStyle(styles.pct);
            }
        }
    }

    private Map<String, Double> avgPassRateByCityTable(List<QualityRow> qualityRows) {
        Map<String, List<BigDecimal>> tmp = new HashMap<>();
        for (QualityRow q : qualityRows) {
            if (q.passRate == null || q.datasourceId == null || q.tableEn == null) {
                continue;
            }
            tmp.computeIfAbsent(q.datasourceId + "|" + q.tableEn, k -> new ArrayList<>()).add(q.passRate);
        }
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, List<BigDecimal>> e : tmp.entrySet()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal v : e.getValue()) {
                sum = sum.add(v);
            }
            out.put(e.getKey(), sum.divide(BigDecimal.valueOf(e.getValue().size()), 6, RoundingMode.HALF_UP).doubleValue());
        }
        return out;
    }

    private void writeSubmit(Workbook wb, Styles styles, Map<String, SubmitStat> submitStats, LocalDate statDate) {
        Sheet sheet = wb.createSheet("数据报送情况");
        setWidths(sheet, 8, 28, 18, 32, 22, 14, 20, 16);
        Row title = sheet.createRow(0);
        setCell(title, 0, "主要信息项", styles.header);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
        setCell(title, 5, statDate == null ? "" : String.valueOf(statDate), styles.headerRed);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 5, 7));
        writeHeaderRow(sheet, 1, new String[]{
                "序号", "市名", "业务名称", "表名", "表英文名", "前置机数据量", "最新报送日期", "备注"
        }, styles.header);
        int r = 2;
        int seq = 1;
        for (SubmitStat st : submitStats.values()) {
            Row row = sheet.createRow(r++);
            setCell(row, 0, seq++, styles.bodyCenter);
            setCell(row, 1, st.cityName, styles.body);
            setCell(row, 2, st.bizName, styles.body);
            setCell(row, 3, st.cnName, styles.body);
            setCell(row, 4, st.tableEn, styles.body);
            setCell(row, 5, st.count, styles.bodyCenter);
            setCell(row, 6, st.latest == null ? "" : TS.format(st.latest), styles.bodyCenter);
            setCell(row, 7, st.error == null ? "" : st.error, styles.body);
        }
    }

    private void writeCityQuality(Workbook wb, Styles styles, List<QualityRow> qualityRows, String dateCn) {
        Sheet sheet = wb.createSheet("下级部门数据质量统计情况");
        setWidths(sheet, 10, 10, 28, 20, 28, 18, 16, 10, 36, 12, 12, 14, 12, 22);
        // two-row header like template
        Row r0 = sheet.createRow(0);
        Row r1 = sheet.createRow(1);
        String[] top = {"", "ID", "下级部门名称", "英文表名", "中文表名", "中文字段", "英文字段", "校验等级", "校验规则",
                "查询日期" + dateCn, "", "", "", ""};
        for (int i = 0; i < top.length; i++) {
            setCell(r0, i, top[i], styles.header);
        }
        String[] sub = {"", "", "", "", "", "", "", "", "",
                "总数据量\n(前置库)", "总问题数据量", "下级部门确认符合实际业务情况，可以不予调整数据量",
                "下级部门上报数据符合率", "下级部门反馈"};
        for (int i = 0; i < sub.length; i++) {
            setCell(r1, i, sub[i], styles.header);
        }
        // merges
        for (int c : new int[]{1, 2, 3, 4, 5, 6, 7, 8}) {
            sheet.addMergedRegion(new CellRangeAddress(0, 1, c, c));
        }
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 9, 13));
        sheet.getRow(0).setHeightInPoints(24);
        sheet.getRow(1).setHeightInPoints(48);

        int r = 2;
        for (QualityRow q : qualityRows) {
            Row row = sheet.createRow(r);
            setCell(row, 0, "", styles.body);
            setCell(row, 1, q.ruleId, styles.bodyCenter);
            setCell(row, 2, q.cityName, styles.body);
            setCell(row, 3, q.tableEn, styles.body);
            setCell(row, 4, q.cnName, styles.body);
            setCell(row, 5, q.cnField, styles.body);
            setCell(row, 6, StringUtils.defaultString(q.columnName), styles.body);
            setCell(row, 7, q.level, styles.bodyCenter);
            setCell(row, 8, q.ruleText, styles.bodyWrap);
            setCell(row, 9, q.totalCount, styles.bodyCenter);
            setCell(row, 10, q.problemCount, styles.bodyCenter);
            setCell(row, 11, 0, styles.bodyCenter);
            Cell rate = row.createCell(12);
            int excelRow = r + 1;
            rate.setCellFormula("IF(J" + excelRow + "=0,\"\",1-(K" + excelRow + "-L" + excelRow + ")/J" + excelRow + ")");
            rate.setCellStyle(styles.pct);
            setCell(row, 13, "", styles.body);
            r++;
        }
        if (qualityRows.isEmpty()) {
            Row row = sheet.createRow(2);
            setCell(row, 2, "（统计日截止前无质检执行结果）", styles.body);
        }
    }

    private void writeProvQuality(Workbook wb, Styles styles, List<QualityRow> qualityRows, String dateCn) {
        Sheet sheet = wb.createSheet("省局报送数据质量统计情况");
        setWidths(sheet, 10, 10, 28, 10, 18, 16, 36, 12, 14, 14, 12, 18);
        Row r0 = sheet.createRow(0);
        Row r1 = sheet.createRow(1);
        String[] top = {"", "ID", "中文表名", "校验等级", "中文字段", "英文字段", "检验规则",
                "查询日期" + dateCn, "", "", "", ""};
        for (int i = 0; i < top.length; i++) {
            setCell(r0, i, top[i], styles.header);
        }
        String[] sub = {"", "", "", "", "", "", "",
                "总数据量\n(原始库)", "总问题数据量（原始库）",
                "下级部门确认符合实际业务情况，可以不予调整数据量", "上报数据符合率", "下级部门反馈"};
        for (int i = 0; i < sub.length; i++) {
            setCell(r1, i, sub[i], styles.header);
        }
        for (int c : new int[]{1, 2, 3, 4, 5, 6}) {
            sheet.addMergedRegion(new CellRangeAddress(0, 1, c, c));
        }
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 7, 11));
        sheet.getRow(0).setHeightInPoints(24);
        sheet.getRow(1).setHeightInPoints(48);

        Map<String, List<QualityRow>> byRule = new LinkedHashMap<>();
        for (QualityRow q : qualityRows) {
            String key = StringUtils.defaultIfBlank(q.ruleId, String.valueOf(q.jobId)) + "|" + StringUtils.defaultString(q.cnName);
            byRule.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
        }

        int r = 2;
        for (List<QualityRow> group : byRule.values()) {
            QualityRow first = group.get(0);
            long total = group.stream().mapToLong(x -> x.totalCount).max().orElse(0L);
            long problem = group.stream().mapToLong(x -> x.problemCount).sum();
            Row row = sheet.createRow(r);
            setCell(row, 0, "", styles.body);
            setCell(row, 1, first.ruleId, styles.bodyCenter);
            setCell(row, 2, first.cnName, styles.body);
            setCell(row, 3, first.level, styles.bodyCenter);
            setCell(row, 4, first.cnField, styles.body);
            setCell(row, 5, StringUtils.defaultString(first.columnName), styles.body);
            setCell(row, 6, first.ruleText, styles.bodyWrap);
            setCell(row, 7, total, styles.bodyCenter);
            setCell(row, 8, problem, styles.bodyCenter);
            setCell(row, 9, 0, styles.bodyCenter);
            Cell rate = row.createCell(10);
            int excelRow = r + 1;
            rate.setCellFormula("IF(H" + excelRow + "=0,\"\",1-(I" + excelRow + "-J" + excelRow + ")/H" + excelRow + ")");
            rate.setCellStyle(styles.pct);
            setCell(row, 11, "", styles.body);
            r++;
        }
        if (byRule.isEmpty()) {
            Row row = sheet.createRow(2);
            setCell(row, 2, "（统计日截止前无质检执行结果）", styles.body);
        }
    }

    private boolean writeChecklistsZip(Path zipPath, List<QualityRow> qualityRows, int sampleLimit,
                                       int detailMaxRows, LocalDate statDate) throws IOException {
        Map<String, List<QualityRow>> byCity = qualityRows.stream()
                .filter(q -> q.problemCount > 0)
                .collect(Collectors.groupingBy(q -> q.cityName, LinkedHashMap::new, Collectors.toList()));
        if (byCity.isEmpty()) {
            return false;
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, List<QualityRow>> e : byCity.entrySet()) {
                String city = e.getKey();
                byte[] xlsx = buildCityChecklist(city, e.getValue(), sampleLimit, detailMaxRows, statDate);
                String safe = city.replaceAll("[\\\\/:*?\"<>|]", "_");
                zos.putNextEntry(new ZipEntry("整改清单-" + safe + "-"
                        + (statDate == null ? "" : DAY.format(statDate)) + ".xlsx"));
                zos.write(xlsx);
                zos.closeEntry();
            }
        }
        return true;
    }

    private byte[] buildCityChecklist(String city, List<QualityRow> rows, int sampleLimit,
                                      int detailMaxRows, LocalDate statDate) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Styles styles = new Styles(wb);
            writeChecklistOverview(wb, styles, city, rows, sampleLimit, statDate);
            writeChecklistDetail(wb, styles, city, rows, detailMaxRows);
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private void writeChecklistOverview(Workbook wb, Styles styles, String city, List<QualityRow> rows,
                                        int sampleLimit, LocalDate statDate) {
        Sheet sheet = wb.createSheet("整改概况");
        writeHeaderRow(sheet, 0, new String[]{
                "下发日期", "统计截止日期", "下级部门名称", "中文表名", "英文表名", "规则ID", "校验等级",
                "中文字段", "英文字段", "校验规则", "总数据量", "问题数据量", "符合率",
                "问题样例", "整改要求", "反馈截止日", "下级部门反馈", "是否申请不予调整", "复检结果"
        }, styles.header);
        int r = 1;
        String today = String.valueOf(LocalDate.now());
        for (QualityRow q : rows) {
            String sample = loadErrorSample(q.jobExecutionId, sampleLimit);
            Row row = sheet.createRow(r++);
            setCell(row, 0, today, styles.bodyCenter);
            setCell(row, 1, String.valueOf(statDate), styles.bodyCenter);
            setCell(row, 2, city, styles.body);
            setCell(row, 3, q.cnName, styles.body);
            setCell(row, 4, q.tableEn, styles.body);
            setCell(row, 5, q.ruleId, styles.bodyCenter);
            setCell(row, 6, q.level, styles.bodyCenter);
            setCell(row, 7, q.cnField, styles.body);
            setCell(row, 8, StringUtils.defaultString(q.columnName), styles.body);
            setCell(row, 9, q.ruleText, styles.bodyWrap);
            setCell(row, 10, q.totalCount, styles.bodyCenter);
            setCell(row, 11, q.problemCount, styles.bodyCenter);
            setCell(row, 12, q.passRate == null ? "" : q.passRate.doubleValue(), styles.pct);
            setCell(row, 13, sample, styles.bodyWrap);
            setCell(row, 14, "请限期修正源端并重推；历史数据如需不予调整请在反馈栏说明。详见「问题明细」sheet。", styles.bodyWrap);
            setCell(row, 15, "", styles.body);
            setCell(row, 16, "", styles.body);
            setCell(row, 17, "", styles.body);
            setCell(row, 18, "", styles.body);
        }
    }

    private void writeChecklistDetail(Workbook wb, Styles styles, String city, List<QualityRow> rows, int detailMaxRows) {
        Sheet sheet = wb.createSheet("问题明细");
        // meta columns + dynamic error CSV columns collected across rules
        List<String> metaHeaders = Arrays.asList(
                "下级部门名称", "规则ID", "规则名称", "中文表名", "英文表名", "校验等级", "中文字段", "英文字段");
        LinkedHashMap<String, Integer> dataColIndex = new LinkedHashMap<>();
        List<DetailRow> all = new ArrayList<>();
        int remaining = detailMaxRows;
        for (QualityRow q : rows) {
            if (remaining <= 0) {
                break;
            }
            ErrorDataPage page = loadErrorDataAll(q.jobExecutionId, remaining);
            for (String col : page.columns) {
                dataColIndex.putIfAbsent(col, dataColIndex.size());
            }
            for (Map<String, Object> data : page.rows) {
                DetailRow dr = new DetailRow();
                dr.quality = q;
                dr.data = data;
                all.add(dr);
                remaining--;
                if (remaining <= 0) {
                    break;
                }
            }
        }

        List<String> headers = new ArrayList<>(metaHeaders);
        headers.addAll(dataColIndex.keySet());
        writeHeaderRow(sheet, 0, headers.toArray(new String[0]), styles.header);
        int r = 1;
        for (DetailRow dr : all) {
            Row row = sheet.createRow(r++);
            QualityRow q = dr.quality;
            setCell(row, 0, city, styles.body);
            setCell(row, 1, q.ruleId, styles.bodyCenter);
            setCell(row, 2, q.ruleText, styles.body);
            setCell(row, 3, q.cnName, styles.body);
            setCell(row, 4, q.tableEn, styles.body);
            setCell(row, 5, q.level, styles.bodyCenter);
            setCell(row, 6, q.cnField, styles.body);
            setCell(row, 7, StringUtils.defaultString(q.columnName), styles.body);
            for (Map.Entry<String, Integer> e : dataColIndex.entrySet()) {
                Object v = dr.data == null ? null : dr.data.get(e.getKey());
                setCell(row, metaHeaders.size() + e.getValue(), v == null ? "" : String.valueOf(v), styles.body);
            }
        }
        if (all.isEmpty()) {
            Row row = sheet.createRow(1);
            setCell(row, 0, city, styles.body);
            setCell(row, 2, "（无错误明细文件或尚未产出错误数据）", styles.body);
        } else if (remaining <= 0) {
            log.warn("city={} detail truncated at maxRows={}", city, detailMaxRows);
        }
        // freeze header
        sheet.createFreezePane(0, 1);
    }

    private ErrorDataPage loadErrorDataAll(Long executionId, int maxRows) {
        ErrorDataPage out = new ErrorDataPage();
        if (executionId == null || maxRows <= 0) {
            return out;
        }
        final int pageSize = 1000;
        int pageNumber = 1;
        try {
            while (out.rows.size() < maxRows) {
                Object pageObj = jobExecutionErrorDataService.readErrorDataPage(executionId, pageNumber, pageSize);
                if (!(pageObj instanceof io.datavines.common.entity.ListWithQueryColumn)) {
                    break;
                }
                io.datavines.common.entity.ListWithQueryColumn page =
                        (io.datavines.common.entity.ListWithQueryColumn) pageObj;
                if (out.columns.isEmpty() && CollectionUtils.isNotEmpty(page.getColumns())) {
                    for (io.datavines.common.entity.QueryColumn c : page.getColumns()) {
                        if (c != null && StringUtils.isNotBlank(c.getName())) {
                            out.columns.add(c.getName());
                        }
                    }
                }
                List<Map<String, Object>> list = page.getResultList();
                if (CollectionUtils.isEmpty(list)) {
                    break;
                }
                for (Map<String, Object> row : list) {
                    out.rows.add(row);
                    if (out.rows.size() >= maxRows) {
                        break;
                    }
                }
                if (list.size() < pageSize) {
                    break;
                }
                if (page.getTotalCount() > 0 && out.rows.size() >= page.getTotalCount()) {
                    break;
                }
                pageNumber++;
                // safety: avoid infinite loop
                if (pageNumber > 10000) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("load error detail failed executionId={}: {}", executionId, e.getMessage());
        }
        return out;
    }

    private String loadErrorSample(Long executionId, int limit) {
        if (executionId == null) {
            return "";
        }
        try {
            Object page = jobExecutionErrorDataService.readErrorDataPage(executionId, 1, Math.min(limit, 50));
            List<?> list = null;
            if (page instanceof io.datavines.common.entity.ListWithQueryColumn) {
                list = ((io.datavines.common.entity.ListWithQueryColumn) page).getResultList();
            } else if (page instanceof Map) {
                Object raw = ((Map<?, ?>) page).get("resultList");
                if (raw instanceof List) {
                    list = (List<?>) raw;
                }
            }
            if (list == null || list.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Object row : list) {
                if (n++ >= limit) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(String.valueOf(row));
            }
            String s = sb.toString();
            return s.length() > 2000 ? s.substring(0, 2000) + "..." : s;
        } catch (Exception e) {
            log.warn("read error sample failed executionId={}: {}", executionId, e.getMessage());
            return "";
        }
    }

    private static void writeHeaderRow(Sheet sheet, int r, String[] vals, CellStyle style) {
        Row row = sheet.createRow(r);
        for (int i = 0; i < vals.length; i++) {
            setCell(row, i, vals[i], style);
        }
    }

    private static void setCell(Row row, int c, Object v, CellStyle style) {
        Cell cell = row.createCell(c);
        if (v == null) {
            cell.setCellValue("");
        } else if (v instanceof Number) {
            cell.setCellValue(((Number) v).doubleValue());
        } else {
            cell.setCellValue(String.valueOf(v));
        }
        cell.setCellStyle(style);
    }

    private static void setWidths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
    }

    private static String col(int zeroBased) {
        return org.apache.poi.ss.util.CellReference.convertNumToColString(zeroBased);
    }

    private static void applyBorder(Sheet sheet, CellRangeAddress region) {
        RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet);
    }

    private static class Styles {
        final CellStyle header;
        final CellStyle headerRed;
        final CellStyle body;
        final CellStyle bodyCenter;
        final CellStyle bodyWrap;
        final CellStyle pct;

        Styles(Workbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setFontName("微软雅黑");
            bold.setFontHeightInPoints((short) 11);

            Font normal = wb.createFont();
            normal.setFontName("微软雅黑");
            normal.setFontHeightInPoints((short) 10);

            Font red = wb.createFont();
            red.setBold(true);
            red.setColor(IndexedColors.RED.getIndex());
            red.setFontName("微软雅黑");

            header = wb.createCellStyle();
            header.setFont(bold);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            header.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            thin(header);

            headerRed = wb.createCellStyle();
            headerRed.cloneStyleFrom(header);
            headerRed.setFont(red);

            body = wb.createCellStyle();
            body.setFont(normal);
            body.setVerticalAlignment(VerticalAlignment.CENTER);
            thin(body);

            bodyCenter = wb.createCellStyle();
            bodyCenter.cloneStyleFrom(body);
            bodyCenter.setAlignment(HorizontalAlignment.CENTER);

            bodyWrap = wb.createCellStyle();
            bodyWrap.cloneStyleFrom(body);
            bodyWrap.setWrapText(true);

            pct = wb.createCellStyle();
            pct.cloneStyleFrom(bodyCenter);
            pct.setDataFormat(wb.createDataFormat().getFormat("0.00%"));
        }

        private static void thin(CellStyle s) {
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
        }
    }

    public static class GenerateResult {
        public String ledgerPath;
        public String checklistZipPath;
    }

    private static class RuleMeta {
        String ruleId;
        String ruleName;
        String tableEn;
        String tableCn;
        String cnField;
        String level;
    }

    private static class SubmitStat {
        Long datasourceId;
        String cityName;
        String cnName;
        String bizName;
        String tableEn;
        long count;
        LocalDateTime latest;
        String error;
    }

    private static class QualityRow {
        Long datasourceId;
        String cityName;
        String tableEn;
        String cnName;
        Long jobId;
        String ruleId;
        String columnName;
        String cnField;
        String metricName;
        String ruleText;
        String level;
        long totalCount;
        long problemCount;
        BigDecimal passRate;
        Long jobExecutionId;
        String errorDataStorageType;
        String errorDataStorageParameter;
        String errorDataFileName;
    }

    private static class ErrorDataPage {
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
    }

    private static class DetailRow {
        QualityRow quality;
        Map<String, Object> data;
    }
}
