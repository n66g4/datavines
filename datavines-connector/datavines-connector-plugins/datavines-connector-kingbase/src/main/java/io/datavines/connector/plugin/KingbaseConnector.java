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

import io.datavines.common.datasource.jdbc.BaseJdbcDataSourceInfo;
import io.datavines.common.datasource.jdbc.entity.TableInfo;
import io.datavines.common.param.ConnectorResponse;
import io.datavines.common.param.GetTablesRequestParam;
import io.datavines.common.utils.StringUtils;
import io.datavines.connector.api.DataSourceClient;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kingbase connector: catalog/job table listing excludes system schemas and views.
 */
public class KingbaseConnector extends JdbcConnector {

    private static final Set<String> SYSTEM_SCHEMAS = new HashSet<>(Arrays.asList(
            "information_schema",
            "pg_catalog",
            "sys_catalog",
            "sys",
            "sysmac",
            "kb_monitor",
            "dbms_sql",
            "perf",
            "anon",
            "xlog_record_read",
            "src_restrict",
            "pg_toast"
    ));

    public KingbaseConnector(DataSourceClient dataSourceClient) {
        super(dataSourceClient);
    }

    @Override
    public BaseJdbcDataSourceInfo getDatasourceInfo(Map<String, String> param) {
        return new KingbaseDataSourceInfo(param);
    }

    @Override
    public ResultSet getMetadataDatabases(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        return metaData.getCatalogs();
    }

    @Override
    public ResultSet getMetadataTables(DatabaseMetaData metaData, String catalog, String schema) throws SQLException {
        // business tables only — exclude VIEW / FOREIGN TABLE (sys_stat_*, all_*, dba_*, ...)
        return metaData.getTables(catalog, schema, null, new String[]{TABLE});
    }

    @Override
    @SuppressWarnings("unchecked")
    public ConnectorResponse getTables(GetTablesRequestParam param) throws SQLException {
        ConnectorResponse response = super.getTables(param);
        if (response == null || response.getResult() == null) {
            return response;
        }
        List<TableInfo> raw = (List<TableInfo>) response.getResult();
        List<TableInfo> filtered = new ArrayList<>();
        for (TableInfo table : raw) {
            if (table == null || StringUtils.isEmpty(table.getName())) {
                continue;
            }
            // TableInfo.database holds JDBC schema when filled by JdbcConnector.getTables
            if (isSystemSchema(table.getDatabase())) {
                continue;
            }
            if (isSystemTableName(table.getName())) {
                continue;
            }
            filtered.add(table);
        }
        response.setResult(filtered);
        return response;
    }

    private static boolean isSystemSchema(String schema) {
        if (StringUtils.isEmpty(schema)) {
            return false;
        }
        return SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT));
    }

    private static boolean isSystemTableName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if ("dual".equals(n)) {
            return true;
        }
        return n.startsWith("sys_")
                || n.startsWith("pg_")
                || n.startsWith("all_")
                || n.startsWith("dba_")
                || n.startsWith("user_")
                || n.startsWith("v$")
                || n.startsWith("gv$")
                || n.startsWith("kdb_")
                || n.startsWith("hm_");
    }
}
