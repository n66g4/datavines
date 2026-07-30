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
package io.datavines.connector.plugin;

import io.datavines.common.param.ConnectorResponse;
import io.datavines.common.param.TestConnectionRequestParam;
import io.datavines.common.utils.JSONUtils;
import io.datavines.common.utils.StringUtils;
import io.datavines.connector.api.Connector;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FileConnector implements Connector {

    @Override
    public List<String> keyProperties() {
        return Collections.emptyList();
    }

    @Override
    public ConnectorResponse testConnect(TestConnectionRequestParam param) {
        ConnectorResponse.ConnectorResponseBuilder builder = ConnectorResponse.builder();
        try {
            if (param == null || StringUtils.isEmpty(param.getDataSourceParam())) {
                return builder.status(ConnectorResponse.Status.ERROR)
                        .errorMsg("dataSourceParam is required")
                        .result(false)
                        .build();
            }
            Map<String, String> map = JSONUtils.toMap(param.getDataSourceParam());
            String dir = map == null ? null : map.get("data_dir");
            if (StringUtils.isEmpty(dir)) {
                return builder.status(ConnectorResponse.Status.ERROR)
                        .errorMsg("data_dir is required for CSV file storage")
                        .result(false)
                        .build();
            }
            File path = new File(dir);
            if (!path.exists() && !path.mkdirs()) {
                return builder.status(ConnectorResponse.Status.ERROR)
                        .errorMsg("cannot create CSV data_dir: " + dir)
                        .result(false)
                        .build();
            }
            if (!path.isDirectory() || !path.canWrite()) {
                return builder.status(ConnectorResponse.Status.ERROR)
                        .errorMsg("CSV data_dir is not a writable directory: " + dir)
                        .result(false)
                        .build();
            }
            return builder.status(ConnectorResponse.Status.SUCCESS).result(true).build();
        } catch (Exception e) {
            return builder.status(ConnectorResponse.Status.ERROR)
                    .errorMsg(e.getMessage())
                    .result(false)
                    .build();
        }
    }
}
