package com.app.master.service.core.config;

import com.app.master.service.core.dto.constants.Constant;
import com.app.master.service.core.service.tenant.TenantDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource dataSource() {
        DataSource dataSource = DataSourceBuilder
                .create()
                .driverClassName(driver)
                .url(url)
                .username(username)
                .password(password).build();

        AbstractRoutingDataSource routingDataSource = new TenantDataSource();
        routingDataSource.setTargetDataSources(Map.of(Constant.DEFAULT, dataSource));
        routingDataSource.setDefaultTargetDataSource(dataSource);
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

}