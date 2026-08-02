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
package io.datavines.server.repository.service.job.importing;

import com.fasterxml.jackson.databind.JsonNode;
import io.datavines.common.utils.JSONUtils;
import io.datavines.common.utils.StringUtils;
import io.datavines.connector.api.utils.SqlUtils;
import io.datavines.core.exception.DataVinesServerException;
import io.datavines.server.api.dto.bo.job.RuleImportItem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parse Excel/CSV/SubmitJob JSON into rule import items.
 */
public final class RuleImportFileParser {

    private RuleImportFileParser() {
    }

    public static List<RuleImportItem> parse(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new DataVinesServerException("import file is empty");
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return parseJson(content);
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseExcel(content);
        }
        if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
            return parseCsv(content);
        }
        // sniff
        String head = new String(content, 0, Math.min(content.length, 200), StandardCharsets.UTF_8).trim();
        if (head.startsWith("{") || head.startsWith("[")) {
            return parseJson(content);
        }
        return parseCsv(content);
    }

    public static List<RuleImportItem> parseJson(byte[] content) {
        int offset = 0;
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        JsonNode root = JSONUtils.parseNode(new String(content, offset, content.length - offset, StandardCharsets.UTF_8));
        if (root == null) {
            throw new DataVinesServerException("invalid json");
        }
        List<RuleImportItem> items = new ArrayList<>();
        String groupName = text(root, "name");

        JsonNode metricList = null;
        if (root.has("parameter") && root.get("parameter").has("metricParameterList")) {
            metricList = root.get("parameter").get("metricParameterList");
        } else if (root.has("metricParameterList")) {
            metricList = root.get("metricParameterList");
        } else if (root.isArray()) {
            metricList = root;
        }

        if (metricList == null || !metricList.isArray() || metricList.size() == 0) {
            throw new DataVinesServerException("json must contain parameter.metricParameterList");
        }

        for (JsonNode node : metricList) {
            JsonNode mp = node.has("metricParameter") ? node.get("metricParameter") : node;
            RuleImportItem item = new RuleImportItem();
            item.setGroupName(groupName);
            item.setMetricType(defaultStr(text(node, "metricType"), "custom_count_sql"));
            item.setRuleId(firstNonEmpty(text(mp, "rule_id"), text(node, "rule_id")));
            item.setRuleName(firstNonEmpty(text(mp, "rule_name"), text(node, "rule_name"), text(node, "name")));
            item.setTable(firstNonEmpty(text(mp, "table"), text(node, "table")));
            item.setMetricDatabase(firstNonEmpty(text(mp, "metric_database"), text(mp, "database")));
            item.setInvalidateItemsSql(firstNonEmpty(text(mp, "invalidate_items_sql"), text(node, "invalidate_items_sql")));
            item.setTagName(firstNonEmpty(
                    text(mp, "tag_name"), text(mp, "business_tag"), text(mp, "业务标签"),
                    text(node, "tag_name"), text(node, "business_tag"), text(node, "业务标签"),
                    text(root, "tag_name"), text(root, "business_tag"), text(root, "业务标签")));
            item.setExpectedValue(defaultStr(text(node.path("expectedParameter"), "expected_value"),
                    defaultStr(text(node, "expected_value"), "0")));
            item.setResultFormula(defaultStr(text(node, "resultFormula"), "count"));
            item.setOperator(defaultStr(text(node, "operator"), "eq"));
            if (node.has("threshold") && !node.get("threshold").isNull()) {
                item.setThreshold(node.get("threshold").asDouble(0));
            }
            validateItem(item);
            items.add(item);
        }
        return items;
    }

    public static List<RuleImportItem> parseExcel(byte[] content) {
        try (InputStream in = new ByteArrayInputStream(content);
             Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new DataVinesServerException("excel has no sheet");
            }
            Iterator<Row> rows = sheet.iterator();
            if (!rows.hasNext()) {
                throw new DataVinesServerException("excel is empty");
            }
            Map<String, Integer> header = headerIndex(rows.next());
            List<RuleImportItem> items = new ArrayList<>();
            DataFormatter formatter = new DataFormatter();
            while (rows.hasNext()) {
                Row row = rows.next();
                if (isBlankRow(row, formatter)) {
                    continue;
                }
                RuleImportItem item = fromMap(cellMap(row, header, formatter));
                validateItem(item);
                items.add(item);
            }
            if (items.isEmpty()) {
                throw new DataVinesServerException("no rule rows in excel");
            }
            return items;
        } catch (DataVinesServerException e) {
            throw e;
        } catch (Exception e) {
            throw new DataVinesServerException("parse excel failed: " + e.getMessage());
        }
    }

    public static List<RuleImportItem> parseCsv(byte[] content) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (StringUtils.isEmpty(headerLine)) {
                throw new DataVinesServerException("csv is empty");
            }
            if (headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
            }
            String[] headers = splitCsvLine(headerLine);
            Map<String, Integer> header = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                header.put(normalizeHeader(headers[i]), i);
            }
            List<RuleImportItem> items = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (StringUtils.isEmpty(line.trim())) {
                    continue;
                }
                String[] cols = splitCsvLine(line);
                Map<String, String> map = new HashMap<>();
                for (Map.Entry<String, Integer> e : header.entrySet()) {
                    int idx = e.getValue();
                    if (idx < cols.length) {
                        map.put(e.getKey(), cols[idx]);
                    }
                }
                RuleImportItem item = fromMap(map);
                validateItem(item);
                items.add(item);
            }
            if (items.isEmpty()) {
                throw new DataVinesServerException("no rule rows in csv");
            }
            return items;
        } catch (DataVinesServerException e) {
            throw e;
        } catch (Exception e) {
            throw new DataVinesServerException("parse csv failed: " + e.getMessage());
        }
    }

    public static byte[] buildCsvTemplate() {
        String csv = "rule_id,rule_name,业务标签,table,metric_database,invalidate_items_sql,expected_value,result_formula,operator,threshold,metric_type\n"
                + "1,demo_not_null,示例业务,,demo_db,\"SELECT * FROM demo_table WHERE demo_col IS NULL\",0,count,eq,0,custom_count_sql\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] buildExcelTemplate() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("rules");
            // table 可空：导入时从 SQL 自动解析；业务标签对应作业 tag_name
            String[] headers = new String[]{
                    "rule_id", "rule_name", "业务标签", "table", "metric_database", "invalidate_items_sql",
                    "expected_value", "result_formula", "operator", "threshold", "metric_type"
            };
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("1");
            sample.createCell(1).setCellValue("demo_not_null");
            sample.createCell(2).setCellValue("示例业务");
            sample.createCell(3).setCellValue("");
            sample.createCell(4).setCellValue("demo_db");
            sample.createCell(5).setCellValue(
                    "SELECT * FROM demo_table WHERE demo_col IS NULL");
            sample.createCell(6).setCellValue("0");
            sample.createCell(7).setCellValue("count");
            sample.createCell(8).setCellValue("eq");
            sample.createCell(9).setCellValue("0");
            sample.createCell(10).setCellValue("custom_count_sql");
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new DataVinesServerException("build excel template failed");
        }
    }

    private static RuleImportItem fromMap(Map<String, String> map) {
        RuleImportItem item = new RuleImportItem();
        item.setRuleId(map.get("rule_id"));
        item.setRuleName(map.get("rule_name"));
        item.setTable(map.get("table"));
        item.setMetricDatabase(firstNonEmpty(map.get("metric_database"), map.get("database")));
        item.setInvalidateItemsSql(map.get("invalidate_items_sql"));
        item.setExpectedValue(defaultStr(map.get("expected_value"), "0"));
        item.setResultFormula(defaultStr(map.get("result_formula"), "count"));
        item.setOperator(defaultStr(map.get("operator"), "eq"));
        item.setMetricType(defaultStr(map.get("metric_type"), "custom_count_sql"));
        item.setTagName(firstNonEmpty(map.get("tag_name"), map.get("business_tag"), map.get("业务标签")));
        String threshold = map.get("threshold");
        if (StringUtils.isNotEmpty(threshold)) {
            try {
                item.setThreshold(Double.parseDouble(threshold.trim()));
            } catch (NumberFormatException ignored) {
                item.setThreshold(0);
            }
        }
        return item;
    }

    private static void validateItem(RuleImportItem item) {
        if (StringUtils.isEmpty(item.getRuleName())) {
            throw new DataVinesServerException("rule_name is required");
        }
        if (StringUtils.isEmpty(item.getInvalidateItemsSql())) {
            throw new DataVinesServerException(
                    "invalidate_items_sql is required for " + item.getRuleName());
        }
        // SQL is source of truth for table when parsable (same as JobServiceImpl)
        String table = SqlUtils.extractPrimaryTableName(item.getInvalidateItemsSql());
        if (StringUtils.isNotEmpty(table)) {
            item.setTable(table);
        } else if (StringUtils.isEmpty(item.getTable())) {
            throw new DataVinesServerException(
                    "table is required (or parsable from SQL) for " + item.getRuleName());
        }
    }

    private static Map<String, Integer> headerIndex(Row row) {
        Map<String, Integer> map = new HashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (Cell cell : row) {
            String name = normalizeHeader(formatter.formatCellValue(cell));
            if (StringUtils.isNotEmpty(name)) {
                map.put(name, cell.getColumnIndex());
            }
        }
        if (!map.containsKey("rule_name") || !map.containsKey("invalidate_items_sql")) {
            throw new DataVinesServerException("header must include rule_name,invalidate_items_sql");
        }
        return map;
    }

    private static Map<String, String> cellMap(Row row, Map<String, Integer> header, DataFormatter formatter) {
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, Integer> e : header.entrySet()) {
            Cell cell = row.getCell(e.getValue());
            map.put(e.getKey(), cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        return map;
    }

    private static boolean isBlankRow(Row row, DataFormatter formatter) {
        if (row == null) {
            return true;
        }
        for (Cell cell : row) {
            if (StringUtils.isNotEmpty(formatter.formatCellValue(cell).trim())) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeHeader(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StringUtils.isNotEmpty(v)) {
                return v;
            }
        }
        return null;
    }

    private static String defaultStr(String value, String def) {
        return StringUtils.isEmpty(value) ? def : value;
    }

    private static String[] splitCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cols.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString().trim());
        return cols.toArray(new String[0]);
    }
}

