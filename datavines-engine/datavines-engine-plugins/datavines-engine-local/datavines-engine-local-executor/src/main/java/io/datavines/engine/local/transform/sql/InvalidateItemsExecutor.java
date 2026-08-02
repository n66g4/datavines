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
package io.datavines.engine.local.transform.sql;

import io.datavines.common.config.Config;
import io.datavines.common.utils.StringUtils;
import io.datavines.connector.api.entity.ResultList;
import io.datavines.connector.api.entity.ResultListWithColumns;
import io.datavines.connector.api.utils.SqlUtils;
import io.datavines.engine.local.api.LocalRuntimeEnvironment;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static io.datavines.common.ConfigConstants.OUTPUT_TABLE;
import static io.datavines.common.ConfigConstants.SQL;

/**
 * Load invalidate-item SQL once into memory so actual-value count and error CSV share one snapshot.
 * (Source accounts are often read-only, so we cannot CREATE TEMP TABLE on the business DB.)
 */
public class InvalidateItemsExecutor implements ITransformExecutor {

    private static final int MAX_ROWS = 100000;

    @Override
    public ResultList execute(Connection connection, Config config, LocalRuntimeEnvironment env) throws Exception {
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            String sql = config.getString(SQL);
            String outputTable = config.getString(OUTPUT_TABLE);
            if (StringUtils.isEmpty(sql) || StringUtils.isEmpty(outputTable)) {
                return new ResultList();
            }

            statement = connection.createStatement();
            // Cap at DB cursor so we never buffer millions of SELECT * rows in heap.
            statement.setMaxRows(MAX_ROWS);
            statement.setFetchSize(1000);
            env.setCurrentStatement(statement);
            resultSet = statement.executeQuery(sql);
            ResultListWithColumns data = SqlUtils.getListWithHeaderFromResultSet(resultSet, 0, MAX_ROWS);
            if (data == null) {
                data = new ResultListWithColumns();
            }
            env.putInvalidateItems(outputTable, data);
            return new ResultList();
        } finally {
            SqlUtils.closeResultSet(resultSet);
            SqlUtils.closeStatement(statement);
            env.setCurrentStatement(null);
        }
    }
}
