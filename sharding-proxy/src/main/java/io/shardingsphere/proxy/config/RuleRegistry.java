/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.proxy.config;

import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.MoreExecutors;
import io.shardingsphere.core.api.config.ShardingRuleConfiguration;
import io.shardingsphere.core.constant.ConnectionMode;
import io.shardingsphere.core.constant.DatabaseType;
import io.shardingsphere.core.constant.properties.ShardingProperties;
import io.shardingsphere.core.constant.properties.ShardingPropertiesConstant;
import io.shardingsphere.core.constant.transaction.TransactionType;
import io.shardingsphere.core.metadata.ShardingMetaData;
import io.shardingsphere.core.rule.DataSourceParameter;
import io.shardingsphere.core.rule.MasterSlaveRule;
import io.shardingsphere.core.rule.ProxyAuthority;
import io.shardingsphere.core.rule.ShardingRule;
import io.shardingsphere.jdbc.orchestration.internal.OrchestrationProxyConfiguration;
import io.shardingsphere.jdbc.orchestration.internal.eventbus.jdbc.state.circuit.JdbcCircuitEventBusEvent;
import io.shardingsphere.jdbc.orchestration.internal.eventbus.jdbc.state.disabled.JdbcDisabledEventBusEvent;
import io.shardingsphere.jdbc.orchestration.internal.eventbus.proxy.config.ProxyConfigurationEventBusEvent;
import io.shardingsphere.proxy.backend.jdbc.datasource.JDBCBackendDataSource;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

/**
 * Sharding rule registry.
 *
 * @author zhangliang
 * @author zhangyonglun
 * @author panjuan
 * @author zhaojun
 * @author wangkai
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public final class RuleRegistry {
    
    private static final RuleRegistry INSTANCE = new RuleRegistry();
    
    private ShardingRule shardingRule;
    
    private MasterSlaveRule masterSlaveRule;
    
    private JDBCBackendDataSource backendDataSource;
    
    private Map<String, DataSourceParameter> dataSourceConfigurationMap;
    
    private boolean showSQL;
    
    private ConnectionMode connectionMode;
    
    private int acceptorSize;
    
    private int executorSize;
    
    private BackendNIOConfiguration backendNIOConfig;
    
    private TransactionType transactionType;
    
    private ProxyAuthority proxyAuthority;
    
    private ShardingMetaData metaData;
    
    private Collection<String> disabledDataSourceNames = new LinkedList<>();
    
    private Collection<String> circuitBreakerDataSourceNames = new LinkedList<>();
    
    /**
     * Get instance of sharding rule registry.
     *
     * @return instance of sharding rule registry
     */
    public static RuleRegistry getInstance() {
        return INSTANCE;
    }
    
    /**
     * Initialize rule registry.
     *
     * @param config yaml proxy configuration
     */
    public synchronized void init(final OrchestrationProxyConfiguration config) {
        Properties properties = null == config.getShardingRule() ? config.getMasterSlaveRule().getProps() : config.getShardingRule().getProps();
        ShardingProperties shardingProperties = new ShardingProperties(null == properties ? new Properties() : properties);
        showSQL = shardingProperties.getValue(ShardingPropertiesConstant.SQL_SHOW);
        connectionMode = ConnectionMode.valueOf(shardingProperties.<String>getValue(ShardingPropertiesConstant.CONNECTION_MODE));
        // TODO just config proxy.transaction.enable here, in future(3.1.0)
        transactionType = shardingProperties.<Boolean>getValue(ShardingPropertiesConstant.PROXY_TRANSACTION_ENABLED) ? TransactionType.XA : TransactionType.LOCAL;
        acceptorSize = shardingProperties.getValue(ShardingPropertiesConstant.ACCEPTOR_SIZE);
        executorSize = shardingProperties.getValue(ShardingPropertiesConstant.EXECUTOR_SIZE);
        // TODO :jiaqi force off use NIO for backend, this feature is not complete yet
        boolean useNIO = false;
        //        boolean proxyBackendUseNio = shardingProperties.getValue(ShardingPropertiesConstant.PROXY_BACKEND_USE_NIO);
        int databaseConnectionCount = shardingProperties.getValue(ShardingPropertiesConstant.PROXY_BACKEND_MAX_CONNECTIONS);
        int connectionTimeoutSeconds = shardingProperties.getValue(ShardingPropertiesConstant.PROXY_BACKEND_CONNECTION_TIMEOUT_SECONDS);
        backendNIOConfig = new BackendNIOConfiguration(useNIO, databaseConnectionCount, connectionTimeoutSeconds);
        shardingRule = new ShardingRule(
                null == config.getShardingRule() ? new ShardingRuleConfiguration() : config.getShardingRule().getShardingRuleConfiguration(), config.getDataSources().keySet());
        if (null != config.getMasterSlaveRule()) {
            masterSlaveRule = new MasterSlaveRule(config.getMasterSlaveRule().getMasterSlaveRuleConfiguration());
        }
        // TODO :jiaqi only use JDBC need connect db via JDBC, netty style should use SQL packet to get metadata
        dataSourceConfigurationMap = config.getDataSources();
        backendDataSource = new JDBCBackendDataSource();
        proxyAuthority = config.getProxyAuthority();
    }
    
    /**
     * Initialize sharding meta data.
     *
     * @param executorService executor service
     */
    public void initShardingMetaData(final ExecutorService executorService) {
        metaData = new ShardingMetaData(getDataSourceURLs(dataSourceConfigurationMap), shardingRule, DatabaseType.MySQL, 
                MoreExecutors.listeningDecorator(executorService), new ProxyTableMetaDataConnectionManager(backendDataSource));
    }
    
    private static Map<String, String> getDataSourceURLs(final Map<String, DataSourceParameter> dataSourceParameters) {
        Map<String, String> result = new LinkedHashMap<>(dataSourceParameters.size(), 1);
        for (Entry<String, DataSourceParameter> entry : dataSourceParameters.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getUrl());
        }
        return result;
    }
    
    /**
     * Judge is master slave only.
     *
     * @return is master slave only
     */
    public boolean isMasterSlaveOnly() {
        return shardingRule.getTableRules().isEmpty() && null != masterSlaveRule;
    }
    
    /**
     * Renew rule registry.
     *
     * @param proxyConfigurationEventBusEvent proxy event bus event.
     */
    @Subscribe
    public void renew(final ProxyConfigurationEventBusEvent proxyConfigurationEventBusEvent) {
        init(new OrchestrationProxyConfiguration(proxyConfigurationEventBusEvent.getDataSources(), proxyConfigurationEventBusEvent.getOrchestrationConfig()));
    }
    
    /**
     * Renew disable dataSource names.
     *
     * @param jdbcDisabledEventBusEvent jdbc disabled event bus event
     */
    @Subscribe
    public void renewDisabledDataSourceNames(final JdbcDisabledEventBusEvent jdbcDisabledEventBusEvent) {
        disabledDataSourceNames = jdbcDisabledEventBusEvent.getDisabledDataSourceNames();
    }
    
    /**
     * Renew circuit breaker dataSource names.
     *
     * @param jdbcCircuitEventBusEvent jdbc circuit event bus event
     */
    @Subscribe
    public void renewCircuitBreakerDataSourceNames(final JdbcCircuitEventBusEvent jdbcCircuitEventBusEvent) {
        circuitBreakerDataSourceNames = jdbcCircuitEventBusEvent.getCircuitBreakerDataSourceNames();
    }
    
    /**
     * Get available data source map.
     *
     * @return available data source map
     */
    public Map<String, DataSourceParameter> getDataSourceConfigurationMap() {
        if (!getDisabledDataSourceNames().isEmpty()) {
            return getAvailableDataSourceConfigurationMap();
        }
        return dataSourceConfigurationMap;
    }
    
    private Map<String, DataSourceParameter> getAvailableDataSourceConfigurationMap() {
        Map<String, DataSourceParameter> result = new LinkedHashMap<>(dataSourceConfigurationMap);
        for (String each : disabledDataSourceNames) {
            result.remove(each);
        }
        return result;
    }
}
