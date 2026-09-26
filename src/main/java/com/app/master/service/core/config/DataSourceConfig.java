package com.app.master.service.core.config;

import com.app.master.service.core.dto.constants.Constant;
import com.app.master.service.core.service.tenant.TenantDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
public class DataSourceConfig {

    @Value("${spring.datasource.driver-class-name}")
    private String driver;
    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.username}")
    private String username;
    @Value("${spring.datasource.password}")
    private String password;

    /**
     * The pooled connection to the default tenant.
     *
     * <p>Its own bean so that {@code spring.datasource.hikari.*} binds to the
     * pool. Previously the annotation sat on the method returning the routing
     * wrapper, and {@link AbstractRoutingDataSource} has no pool settings to
     * bind — so every configured value, {@code maximum-pool-size} included, was
     * silently discarded and the application always ran on Hikari's defaults.
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource defaultTenantDataSource() {
        return DataSourceBuilder
                .create()
                .driverClassName(driver)
                .url(url)
                .username(username)
                .password(password)
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource defaultTenantDataSource) {
        AbstractRoutingDataSource routingDataSource = new TenantDataSource();
        routingDataSource.setTargetDataSources(Map.of(Constant.DEFAULT, defaultTenantDataSource));
        routingDataSource.setDefaultTargetDataSource(defaultTenantDataSource);
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

}