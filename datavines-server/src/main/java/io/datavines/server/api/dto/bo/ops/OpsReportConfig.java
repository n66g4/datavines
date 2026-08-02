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
package io.datavines.server.api.dto.bo.ops;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * config_json schema for ops report profile.
 * <pre>
 * {
 *   "businessTag":"示例业务",
 *   "tables":[{"cnName":"...","byDatasource":{"3":"demo_table"}}],
 *   "timeFields":["create_time","update_time"],
 *   "errorSampleLimit":50,
 *   "errorDetailMaxRows":100000
 * }
 * </pre>
 */
@Data
public class OpsReportConfig {
    /** Workspace catalog tag name; filters jobs by dv_job.tag_name */
    private String businessTag;
    private List<TableMapping> tables;
    private List<String> timeFields;
    private Integer errorSampleLimit = 50;
    /** Max rows written to 问题明细 sheet per city (0/null = default 100000). */
    private Integer errorDetailMaxRows = 100000;

    @Data
    public static class TableMapping {
        private String cnName;
        /** @deprecated use profile-level businessTag; kept for old configs */
        private String bizName;
        /** datasourceId string -> physical table name */
        private Map<String, String> byDatasource;
    }
}
