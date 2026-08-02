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
package io.datavines.connector.api.utils;

import io.datavines.common.exception.DataVinesException;
import io.datavines.common.utils.StringUtils;
import io.datavines.connector.api.entity.QueryColumn;
import io.datavines.connector.api.entity.ResultList;
import io.datavines.connector.api.entity.ResultListWithColumns;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.apache.commons.collections4.CollectionUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.EMPTY;

@Slf4j
public class SqlUtils {

    public static ResultListWithColumns getListWithHeaderFromResultSet(ResultSet rs, Set<String> queryFromsAndJoins) throws SQLException {

        ResultListWithColumns resultListWithColumns = new ResultListWithColumns();

        ResultSetMetaData metaData = rs.getMetaData();

        List<QueryColumn> queryColumns = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String key = getColumnLabel(queryFromsAndJoins, metaData.getColumnLabel(i));
            queryColumns.add(new QueryColumn(key, metaData.getColumnTypeName(i),""));
        }
        resultListWithColumns.setColumns(queryColumns);

        List<Map<String, Object>> resultList = new ArrayList<>();

        try {
            while (rs.next()) {
                resultList.add(getResultObjectMap(rs, metaData));
            }
        } catch (Throwable e) {
            log.error("get result set error: {0}", e);
        }

        resultListWithColumns.setResultList(resultList);
        return resultListWithColumns;
    }

    public static ResultListWithColumns getListWithHeaderFromResultSet(ResultSet rs, int start, int end) throws SQLException {

        ResultListWithColumns resultListWithColumns = new ResultListWithColumns();

        ResultSetMetaData metaData = rs.getMetaData();

        List<QueryColumn> queryColumns = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            queryColumns.add(new QueryColumn(metaData.getColumnLabel(i), metaData.getColumnTypeName(i),""));
        }
        resultListWithColumns.setColumns(queryColumns);
        resultListWithColumns.setResultList(getPage(rs, start, end, metaData));
        return resultListWithColumns;
    }

    public static ResultList getPageFromResultSet(ResultSet rs, int start, int end) throws SQLException {

        ResultList result = new ResultList();
        ResultSetMetaData metaData = rs.getMetaData();
        result.setResultList(getPage(rs, start, end, metaData));
        return result;
    }

    /**
     * Read next {@code (end - start)} rows from a ResultSet that is consumed sequentially.
     * Does NOT call {@link ResultSet#absolute} — many JDBC drivers (and local engine) use
     * TYPE_FORWARD_ONLY cursors where absolute() fails and previously swallowed the error,
     * so only the first page (~1000 rows) was ever written to error CSV.
     */
    private static List<Map<String, Object>> getPage(ResultSet rs, int start, int end, ResultSetMetaData metaData) {

        List<Map<String, Object>> resultList = new ArrayList<>();
        if (end <= start) {
            return resultList;
        }
        int limit = end - start;
        try {
            int fetched = 0;
            while (fetched < limit && rs.next()) {
                resultList.add(getResultObjectMap(rs, metaData));
                fetched++;
            }
        } catch (Throwable e) {
            log.error("get result set error", e);
        }

        return resultList;
    }

    public static ResultList getListFromResultSet(ResultSet rs) throws SQLException {

        ResultList result = new ResultList();
        ResultSetMetaData metaData = rs.getMetaData();

        List<Map<String, Object>> resultList = new ArrayList<>();

        try {
            while (rs.next()) {
                resultList.add(getResultObjectMap(rs, metaData));
            }
        } catch (Throwable e) {
            log.error("get result set error: {0}", e);
        }

        result.setResultList(resultList);
        return result;
    }

    private static Map<String, Object> getResultObjectMap(ResultSet rs, ResultSetMetaData metaData) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();

        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String key = metaData.getColumnLabel(i);
            Object value = rs.getObject(key);
            map.put(key.toLowerCase(), value instanceof byte[] ? new String((byte[]) value) : value);
        }

        return map;
    }

    private static String getColumnLabel(Set<String> columnPrefixes, String columnLabel) {
        if (!CollectionUtils.isEmpty(columnPrefixes)) {
            for (String prefix : columnPrefixes) {
                if (columnLabel.startsWith(prefix)) {
                    return columnLabel.replaceFirst(prefix, EMPTY);
                }
                if (columnLabel.startsWith(prefix.toLowerCase())) {
                    return columnLabel.replaceFirst(prefix.toLowerCase(), EMPTY);
                }
                if (columnLabel.startsWith(prefix.toUpperCase())) {
                    return columnLabel.replaceFirst(prefix.toUpperCase(), EMPTY);
                }
            }
        }

        return columnLabel;
    }

    public static void dropView(String viewName, Connection connection) {
        try (Statement statement = connection.createStatement()){
            if (!StringUtils.isEmptyOrNullStr(viewName)) {
                statement.execute("DROP VIEW " + viewName);
            }
        } catch (Exception e) {
            log.error("drop {} view error",viewName);
        }
    }

    public static void dropView(String viewName, Statement statement) {
        try {
            if (!StringUtils.isEmptyOrNullStr(viewName)) {
                statement.execute("DROP VIEW " + viewName);
            }
        } catch (Exception e) {
            log.error("drop {} view error", viewName);
        }
    }

    public static void closeResultSet(ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (Exception e) {
                log.error("close result set error : ", e);
            }
        }
    }

    public static void closeStatement(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (Exception e) {
                log.error("close statement error : ", e);
            }
        }
    }

    public static void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                log.error("close connection error : ", e);
            }
        }
    }

    public static List<String> extractTablesFromSelect(String sql) {
        List<String> tables;
        try {
            // 解析 SQL 查询语句
            tables = new ArrayList<>(TablesNamesFinder.findTables(sql));
        } catch (Exception e) {
            throw new DataVinesException("extract tables from select error", e);
        }
        return tables;
    }

    /**
     * Best-effort: main table of the outermost SELECT's FROM clause (not JOIN tables).
     * Schema/quotes stripped. Returns null on failure.
     * <p>
     * Note: {@link TablesNamesFinder} order is not FROM-first (JOIN tables may come first),
     * so JOIN lookup/code tables must not be treated as the primary table.
     */
    public static String extractPrimaryTableName(String sql) {
        if (StringUtils.isEmpty(sql)) {
            return null;
        }
        try {
            net.sf.jsqlparser.statement.Statement parsed = CCJSqlParserUtil.parse(sql);
            if (parsed instanceof Select) {
                PlainSelect plainSelect = ((Select) parsed).getPlainSelect();
                if (plainSelect != null) {
                    FromItem fromItem = plainSelect.getFromItem();
                    if (fromItem instanceof Table) {
                        return stripSchemaAndQuotes(((Table) fromItem).getFullyQualifiedName());
                    }
                }
            }
            // Fallback only when FROM is not a plain table (subquery etc.)
            List<String> tables = new ArrayList<>(TablesNamesFinder.findTables(sql));
            if (CollectionUtils.isEmpty(tables)) {
                return null;
            }
            return stripSchemaAndQuotes(tables.get(0));
        } catch (Exception e) {
            log.warn("extract primary table from sql failed: {}", e.getMessage());
            return null;
        }
    }

    /** Common status/filter columns that should not be treated as the metric column. */
    private static final Set<String> FILTER_COLUMN_NAMES = new HashSet<>(Arrays.asList(
            "DATA_STATE", "DELETE_FLAG", "IS_DELETE", "DEL_FLAG"
    ));

    /**
     * Best-effort primary column from WHERE.
     * Real quality SQL often has business_col + DATA_STATE filters; prefer the first
     * non-filter column in appearance order, else the first column.
     */
    public static String extractSingleColumnFromWhere(String sql) {
        if (StringUtils.isEmpty(sql)) {
            return null;
        }
        try {
            net.sf.jsqlparser.statement.Statement parsed = CCJSqlParserUtil.parse(sql);
            if (!(parsed instanceof Select)) {
                return null;
            }
            Select select = (Select) parsed;
            PlainSelect plainSelect = select.getPlainSelect();
            if (plainSelect == null) {
                return null;
            }
            Expression where = plainSelect.getWhere();
            if (where == null) {
                return null;
            }
            List<String> columns = new ArrayList<>();
            where.accept(new ExpressionVisitorAdapter() {
                @Override
                public void visit(Column column) {
                    String name = stripSchemaAndQuotes(column.getColumnName());
                    if (StringUtils.isNotEmpty(name) && !columns.contains(name)) {
                        columns.add(name);
                    }
                }
            });
            if (columns.isEmpty()) {
                return null;
            }
            for (String name : columns) {
                if (!isFilterColumn(name)) {
                    return name;
                }
            }
            return columns.get(0);
        } catch (Exception e) {
            log.warn("extract column from where failed: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isFilterColumn(String name) {
        return StringUtils.isNotEmpty(name)
                && FILTER_COLUMN_NAMES.contains(name.toUpperCase(Locale.ROOT));
    }

    private static String stripSchemaAndQuotes(String name) {
        if (StringUtils.isEmpty(name)) {
            return null;
        }
        String value = name.trim();
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) {
            value = value.substring(dot + 1);
        }
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '`' && last == '`')
                    || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        return StringUtils.isEmpty(value) ? null : value;
    }
}
