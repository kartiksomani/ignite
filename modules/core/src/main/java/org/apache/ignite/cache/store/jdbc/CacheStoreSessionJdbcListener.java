/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.cache.store.jdbc;

import org.apache.ignite.*;
import org.apache.ignite.cache.store.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lifecycle.*;

import javax.cache.*;
import javax.cache.integration.*;
import javax.sql.*;
import java.sql.*;
import java.util.*;

/**
 * Cache store session listener based on JDBC connection.
 * <p>
 * For each session this listener gets a new JDBC connection
 * from provided {@link DataSource} and commits (or rolls
 * back) it when session ends.
 * <p>
 * The connection is stored in store session
 * {@link CacheStoreSession#properties() properties} and can
 * be accessed at any moment by {@link #JDBC_CONN_KEY} key.
 * The listener guarantees that the connection will be
 * available for any store operation. If there is an
 * ongoing cache transaction, all operations within this
 * transaction will be committed or rolled back only when
 * session ends.
 * <p>
 * As an example, here is how the {@link CacheStore#write(Cache.Entry)}
 * method can be implemented if {@link CacheStoreSessionJdbcListener}
 * is configured:
 * <pre name="code" class="java">
 * private static class Store extends CacheStoreAdapter&lt;Integer, Integer&gt; {
 *     &#64;CacheStoreSessionResource
 *     private CacheStoreSession ses;
 *
 *     &#64;Override public void write(Cache.Entry&lt;? extends Integer, ? extends Integer&gt; entry) throws CacheWriterException {
 *         // Get connection from the current session.
 *         Connection conn = ses.<String, Connection>properties().get(CacheStoreSessionJdbcListener.JDBC_CONN_KEY);
 *
 *         // Execute update SQL query.
 *         try {
 *             conn.createStatement().executeUpdate("...");
 *         }
 *         catch (SQLException e) {
 *             throw new CacheWriterException("Failed to update the store.", e);
 *         }
 *     }
 * }
 * </pre>
 * JDBC connection will be automatically created by the listener
 * at the start of the session and closed when it ends.
 */
public class CacheStoreSessionJdbcListener implements CacheStoreSessionListener, LifecycleAware {
    /** Session key for JDBC connection. */
    public static final String JDBC_CONN_KEY = "__jdbc_conn_";

    /** Data source. */
    private DataSource dataSrc;

    /**
     * Sets data source.
     * <p>
     * This is a required parameter. If data source is not set,
     * exception will be thrown on startup.
     *
     * @param dataSrc Data source.
     */
    public void setDataSource(DataSource dataSrc) {
        this.dataSrc = dataSrc;
    }

    /**
     * Gets data source.
     *
     * @return Data source.
     */
    public DataSource getDataSource() {
        return dataSrc;
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteException {
        if (dataSrc == null)
            throw new IgniteException("Data source is required by " + getClass().getSimpleName() + '.');
    }

    /** {@inheritDoc} */
    @Override public void stop() throws IgniteException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void onSessionStart(CacheStoreSession ses) {
        Map<String, Connection> props = ses.properties();

        if (!props.containsKey(JDBC_CONN_KEY)) {
            try {
                Connection conn = dataSrc.getConnection();

                conn.setAutoCommit(false);

                props.put(JDBC_CONN_KEY, conn);
            }
            catch (SQLException e) {
                throw new CacheWriterException("Failed to start store session [tx=" + ses.transaction() + ']', e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void onSessionEnd(CacheStoreSession ses, boolean commit) {
        Connection conn = ses.<String, Connection>properties().remove(JDBC_CONN_KEY);

        if (conn != null) {
            try {
                if (commit)
                    conn.commit();
                else
                    conn.rollback();
            }
            catch (SQLException e) {
                throw new CacheWriterException("Failed to start store session [tx=" + ses.transaction() + ']', e);
            }
            finally {
                U.closeQuiet(conn);
            }
        }
    }
}
