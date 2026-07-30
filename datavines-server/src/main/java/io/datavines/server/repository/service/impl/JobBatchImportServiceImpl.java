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
package io.datavines.server.repository.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.datavines.common.entity.job.BaseJobParameter;
import io.datavines.common.utils.JSONUtils;
import io.datavines.common.utils.StringUtils;
import io.datavines.core.exception.DataVinesServerException;
import io.datavines.server.api.dto.bo.job.JobBatchImportResult;
import io.datavines.server.api.dto.bo.job.JobCreate;
import io.datavines.server.api.dto.bo.job.JobUpdate;
import io.datavines.server.api.dto.bo.job.RuleImportItem;
import io.datavines.server.repository.entity.DataSource;
import io.datavines.server.repository.entity.Job;
import io.datavines.server.repository.service.DataSourceService;
import io.datavines.server.repository.service.JobBatchImportService;
import io.datavines.server.repository.service.JobService;
import io.datavines.server.repository.service.job.importing.RuleImportFileParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service("jobBatchImportService")
public class JobBatchImportServiceImpl implements JobBatchImportService {

    private static final String SPLIT_PER_RULE = "PER_RULE";
    private static final String SPLIT_PER_TABLE = "PER_TABLE";
    private static final String DUP_SKIP = "SKIP";
    private static final String DUP_UPDATE = "UPDATE";
    private static final String DUP_FAIL = "FAIL";

    @Autowired
    private JobService jobService;

    @Autowired
    private DataSourceService dataSourceService;

    @Override
    public JobBatchImportResult importJobs(MultipartFile file,
                                           Long dataSourceId,
                                           Long errorDataStorageId,
                                           String splitMode,
                                           String duplicateStrategy,
                                           String engineType,
                                           Integer runningNow) throws DataVinesServerException {
        if (file == null || file.isEmpty()) {
            throw new DataVinesServerException("import file is required");
        }
        if (dataSourceId == null) {
            throw new DataVinesServerException("dataSourceId is required");
        }
        DataSource dataSource = dataSourceService.getDataSourceById(dataSourceId);
        if (dataSource == null) {
            throw new DataVinesServerException("datasource not found: " + dataSourceId);
        }

        String mode = StringUtils.isEmpty(splitMode) ? SPLIT_PER_RULE : splitMode.trim().toUpperCase(Locale.ROOT);
        String dup = StringUtils.isEmpty(duplicateStrategy) ? DUP_SKIP : duplicateStrategy.trim().toUpperCase(Locale.ROOT);
        String engine = StringUtils.isEmpty(engineType) ? "local" : engineType;
        int runNow = runningNow == null ? 0 : runningNow;

        String defaultDatabase = extractDatabase(dataSource.getParam());

        List<RuleImportItem> items;
        try {
            items = RuleImportFileParser.parse(file.getOriginalFilename(), file.getBytes());
        } catch (DataVinesServerException e) {
            throw e;
        } catch (Exception e) {
            throw new DataVinesServerException("read import file failed: " + e.getMessage());
        }

        for (RuleImportItem item : items) {
            if (StringUtils.isEmpty(item.getMetricDatabase())) {
                item.setMetricDatabase(defaultDatabase);
            }
        }

        List<JobCreate> jobCreates = buildJobCreates(items, mode, dataSourceId, errorDataStorageId, engine, runNow);

        if (DUP_FAIL.equals(dup)) {
            List<String> conflicts = new ArrayList<>();
            for (JobCreate create : jobCreates) {
                if (findExisting(dataSourceId, create.getJobName(), peekTable(create), peekDatabase(create)) != null) {
                    conflicts.add(create.getJobName());
                }
            }
            if (CollectionUtils.isNotEmpty(conflicts)) {
                throw new DataVinesServerException("duplicate jobs exist: " + String.join(",", conflicts));
            }
        }

        JobBatchImportResult result = new JobBatchImportResult();
        for (JobCreate create : jobCreates) {
            String name = create.getJobName();
            try {
                Job existing = findExisting(dataSourceId, name, peekTable(create), peekDatabase(create));
                if (existing != null) {
                    if (DUP_SKIP.equals(dup)) {
                        result.getSkipped().add(name);
                        continue;
                    }
                    if (DUP_UPDATE.equals(dup)) {
                        JobUpdate update = toUpdate(existing, create);
                        jobService.update(update);
                        result.getUpdated().add(name);
                        continue;
                    }
                }
                jobService.create(create);
                result.getCreated().add(name);
            } catch (Exception e) {
                log.warn("import job failed: {}", name, e);
                result.getFailed().add(new JobBatchImportResult.FailedItem(name, e.getMessage()));
            }
        }
        return result;
    }

    @Override
    public byte[] templateExcel() {
        return RuleImportFileParser.buildExcelTemplate();
    }

    @Override
    public byte[] templateCsv() {
        return RuleImportFileParser.buildCsvTemplate();
    }

    private List<JobCreate> buildJobCreates(List<RuleImportItem> items,
                                            String mode,
                                            Long dataSourceId,
                                            Long errorDataStorageId,
                                            String engine,
                                            int runNow) {
        List<JobCreate> list = new ArrayList<>();
        if (SPLIT_PER_TABLE.equals(mode)) {
            Map<String, List<RuleImportItem>> groups = items.stream()
                    .collect(Collectors.groupingBy(i -> {
                        if (StringUtils.isNotEmpty(i.getGroupName())) {
                            return i.getGroupName();
                        }
                        return defaultStr(i.getMetricDatabase(), "") + "." + i.getTable();
                    }, LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<String, List<RuleImportItem>> e : groups.entrySet()) {
                list.add(toJobCreate(e.getKey(), e.getValue(), dataSourceId, errorDataStorageId, engine, runNow));
            }
        } else {
            for (RuleImportItem item : items) {
                list.add(toJobCreate(resolveRuleJobName(item), Collections.singletonList(item),
                        dataSourceId, errorDataStorageId, engine, runNow));
            }
        }
        return list;
    }

    private JobCreate toJobCreate(String jobName,
                                  List<RuleImportItem> metrics,
                                  Long dataSourceId,
                                  Long errorDataStorageId,
                                  String engine,
                                  int runNow) {
        List<BaseJobParameter> parameters = new ArrayList<>();
        for (RuleImportItem item : metrics) {
            parameters.add(toParameter(item));
        }
        JobCreate create = new JobCreate();
        create.setType("DATA_QUALITY");
        create.setDataSourceId(dataSourceId);
        create.setExecutePlatformType("client");
        create.setEngineType(engine);
        create.setParameter(JSONUtils.toJsonString(parameters));
        create.setJobName(jobName);
        create.setRunningNow(runNow);
        create.setIsErrorDataOutputToDataSource(false);
        if (errorDataStorageId != null) {
            create.setErrorDataStorageId(errorDataStorageId);
        }
        create.setRetryTimes(0);
        create.setRetryInterval(1000);
        create.setTimeout(36000);
        return create;
    }

    private BaseJobParameter toParameter(RuleImportItem item) {
        BaseJobParameter p = new BaseJobParameter();
        p.setMetricType(defaultStr(item.getMetricType(), "custom_count_sql"));
        p.setExpectedType("fix_value");
        p.setResultFormula(defaultStr(item.getResultFormula(), "count"));
        p.setOperator(defaultStr(item.getOperator(), "eq"));
        p.setThreshold(item.getThreshold());

        Map<String, Object> metricParameter = new LinkedHashMap<>();
        metricParameter.put("database", item.getMetricDatabase());
        metricParameter.put("table", item.getTable());
        metricParameter.put("filter", "");
        metricParameter.put("invalidate_items_sql", item.getInvalidateItemsSql());
        if (StringUtils.isNotEmpty(item.getRuleId())) {
            metricParameter.put("rule_id", item.getRuleId());
        }
        if (StringUtils.isNotEmpty(item.getRuleName())) {
            metricParameter.put("rule_name", item.getRuleName());
        }
        p.setMetricParameter(metricParameter);

        Map<String, Object> expectedParameter = new LinkedHashMap<>();
        expectedParameter.put("expected_value", defaultStr(item.getExpectedValue(), "0"));
        p.setExpectedParameter(expectedParameter);
        return p;
    }

    private JobUpdate toUpdate(Job existing, JobCreate create) {
        JobUpdate update = new JobUpdate();
        update.setId(existing.getId());
        update.setType(create.getType());
        update.setDataSourceId(create.getDataSourceId());
        update.setDataSourceId2(create.getDataSourceId2());
        update.setExecutePlatformType(create.getExecutePlatformType());
        update.setExecutePlatformParameter(create.getExecutePlatformParameter());
        update.setEngineType(create.getEngineType());
        update.setEngineParameter(create.getEngineParameter());
        update.setParameter(create.getParameter());
        update.setTimeout(create.getTimeout());
        update.setTimeoutStrategy(create.getTimeoutStrategy());
        update.setRetryTimes(create.getRetryTimes());
        update.setRetryInterval(create.getRetryInterval());
        update.setPreSql(create.getPreSql());
        update.setPostSql(create.getPostSql());
        update.setTenantCode(create.getTenantCode());
        update.setEnv(create.getEnv());
        update.setErrorDataStorageId(create.getErrorDataStorageId());
        update.setErrorDataOutputToDataSourceDatabase(create.getErrorDataOutputToDataSourceDatabase());
        update.setIsErrorDataOutputToDataSource(create.getIsErrorDataOutputToDataSource());
        update.setRunningNow(0);
        update.setJobName(create.getJobName());
        return update;
    }

    private Job findExisting(Long dataSourceId, String name, String table, String schema) {
        QueryWrapper<Job> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(Job::getDataSourceId, dataSourceId)
                .eq(Job::getName, name)
                .eq(StringUtils.isNotEmpty(schema), Job::getSchemaName, schema)
                .eq(StringUtils.isNotEmpty(table), Job::getTableName, table);
        List<Job> list = jobService.list(qw);
        return CollectionUtils.isEmpty(list) ? null : list.get(0);
    }

    private String resolveRuleJobName(RuleImportItem item) {
        if (StringUtils.isNotEmpty(item.getRuleName())) {
            return item.getRuleName();
        }
        if (StringUtils.isNotEmpty(item.getRuleId())) {
            return "rule_" + item.getRuleId();
        }
        return item.getTable() + "_rule";
    }

    private String peekTable(JobCreate create) {
        List<BaseJobParameter> list = JSONUtils.toList(create.getParameter(), BaseJobParameter.class);
        if (CollectionUtils.isEmpty(list) || list.get(0).getMetricParameter() == null) {
            return null;
        }
        Object table = list.get(0).getMetricParameter().get("table");
        return table == null ? null : String.valueOf(table);
    }

    private String peekDatabase(JobCreate create) {
        List<BaseJobParameter> list = JSONUtils.toList(create.getParameter(), BaseJobParameter.class);
        if (CollectionUtils.isEmpty(list) || list.get(0).getMetricParameter() == null) {
            return null;
        }
        Object database = list.get(0).getMetricParameter().get("database");
        return database == null ? null : String.valueOf(database);
    }

    private String extractDatabase(String decryptedParamJson) {
        if (StringUtils.isEmpty(decryptedParamJson)) {
            return null;
        }
        Map<String, String> map = JSONUtils.toMap(decryptedParamJson);
        if (map == null) {
            return null;
        }
        return map.get("database");
    }

    private String defaultStr(String value, String def) {
        return StringUtils.isEmpty(value) ? def : value;
    }
}
