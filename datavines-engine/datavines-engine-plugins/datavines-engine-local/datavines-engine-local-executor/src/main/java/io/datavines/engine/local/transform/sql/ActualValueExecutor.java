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
import io.datavines.engine.local.api.LocalRuntimeEnvironment;
import io.datavines.connector.api.entity.ResultList;
import io.datavines.connector.api.entity.ResultListWithColumns;
import io.datavines.connector.api.utils.SqlUtils;
import org.apache.commons.collections4.CollectionUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.datavines.common.ConfigConstants.*;

public class ActualValueExecutor implements ITransformExecutor {

    private static final Pattern AS_ALIAS = Pattern.compile("(?i)\\bas\\s+(\\w+)");

    @Override
    public ResultList execute(Connection connection, Config config, LocalRuntimeEnvironment env) throws Exception {

        String invalidateTable = config.getString(INVALIDATE_ITEMS_TABLE);
        ResultListWithColumns cached = env.getInvalidateItems(invalidateTable);
        if (cached != null) {
            String sql = config.getString(SQL);
            String key = "actual_value";
            if (StringUtils.isNotEmpty(sql)) {
                Matcher matcher = AS_ALIAS.matcher(sql);
                if (matcher.find()) {
                    key = matcher.group(1).toLowerCase();
                }
            }
            int count = CollectionUtils.isEmpty(cached.getResultList()) ? 0 : cached.getResultList().size();
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put(key, String.valueOf(count));
            ResultList resultList = new ResultList();
            resultList.setResultList(Collections.singletonList(dataMap));
            return resultList;
        }

        Statement statement = null;
        ResultSet resultSet = null;
        ResultList resultList;
        try {
            String sql = config.getString(SQL);

            statement = connection.createStatement();
            env.setCurrentStatement(statement);
            resultSet = statement.executeQuery(sql);
            resultList = SqlUtils.getListFromResultSet(resultSet);
            if (CollectionUtils.isNotEmpty(resultList.getResultList())) {
                List<Map<String, Object>> dataList = resultList.getResultList();
                List<Map<String, Object>> newDataList = new ArrayList<>();

                List<String> valueList = new ArrayList<>();
                String key = new ArrayList<>(dataList.get(0).keySet()).get(0);
                for (Map<String, Object> item : dataList) {
                    valueList.addAll(item.values().stream().map(String::valueOf).collect(Collectors.toList()));
                }

                Map<String, Object> dataMap = new HashMap<>();
                dataMap.put(key, String.join("@#@",valueList.toArray(new String[]{})));
                newDataList.add(dataMap);
                resultList.setResultList(newDataList);
            }
        } finally {
            SqlUtils.closeResultSet(resultSet);
            SqlUtils.closeStatement(statement);
            env.setCurrentStatement(null);
        }

        return resultList;
    }
}
