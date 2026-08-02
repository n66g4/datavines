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
package io.datavines.engine.local.api.entity;

import io.datavines.common.config.Config;
import io.datavines.connector.api.ConnectorFactory;
import io.datavines.engine.local.api.utils.LoggerFactory;
import io.datavines.spi.PluginDiscovery;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;

import static io.datavines.common.ConfigConstants.SRC_CONNECTOR_TYPE;

public class ConnectionHolder {

    private Connection connection;

    private final Config config;

    public ConnectionHolder(Config config){
        this.config = config;
    }

    public ConnectionHolder(Connection connection, Config config) {
        this.connection = connection;
        this.config = config;
    }

    public Connection getConnection() throws SQLException {
        // Resolve logger per call: static init would freeze ThreadLocal job logger of the first job.
        Logger logger = LoggerFactory.getLogger(ConnectionHolder.class);
        if (needReconnect()) {
            closeQuietly();
            ConnectorFactory connectorFactory = PluginDiscovery.getMultiKeyPluginDiscovery(ConnectorFactory.class, ConnectorFactory::getPluginNames)
                    .getNewPlugin(config.getString(SRC_CONNECTOR_TYPE));
            connection = connectorFactory.getDataSourceClient().getConnection(config.configMap(), logger);
        }
        return connection;
    }

    private boolean needReconnect() throws SQLException {
        if (connection == null) {
            return true;
        }
        if (connection.isClosed()) {
            return true;
        }
        // Short validity check; do not use a long timeout that blocks the job thread.
        try {
            return !connection.isValid(1);
        } catch (SQLException e) {
            return true;
        }
    }

    private void closeQuietly() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort return to pool
        }
        connection = null;
    }

    public void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
