package com.app.master.service.service.engineering;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * The scanner's read-only route to business data.
 *
 * <p>A second, deliberately tiny pool bound to the {@code veloria_scan_ro}
 * PostgreSQL role, which holds {@code SELECT} and nothing else. That role — not
 * {@code @Transactional(readOnly = true)} — is what makes the read-only guarantee
 * real: the Spring flag sets the JDBC connection read-only and disables Hibernate
 * dirty checking, but a native {@code UPDATE} issued over that connection still
 * reaches the server. A privilege the role does not have cannot be exercised by
 * any statement, however it is written.
 *
 * <h2>Pool size</h2>
 * Two connections, not a copy of the business pool. Only one scan runs globally —
 * the advisory lock guarantees it — so one connection plus a spare is ample, and a
 * second twenty-connection pool per instance would halve how many instances fit
 * inside {@code max_connections}:
 *
 * <pre>
 * max_connections 100, less 3 superuser-reserved → 97 usable
 * business 20/instance + engineering 2/instance
 *   2 instances → 44   3 instances → 66   4 instances → 88 (only 9 spare)
 * </pre>
 *
 * <h2>When the role is absent</h2>
 * The bean is created regardless, but the first connection attempt fails loudly.
 * It is not defaulted to the write datasource: a scanner silently holding write
 * privileges is exactly the condition §26 and §67 forbid, and a broken scan is
 * safer than an unnoticed one.
 */
@Configuration
@Slf4j
public class EngineeringDataSourceConfig {

    @Value("${spring.datasource.driver-class-name}")
    private String driver;

    @Value("${engineering.datasource.url:${spring.datasource.url}}")
    private String url;

    @Value("${engineering.datasource.username:veloria_scan_ro}")
    private String username;

    @Value("${engineering.datasource.password:}")
    private String password;

    @Value("${engineering.datasource.maximum-pool-size:2}")
    private int maximumPoolSize;

    @Bean(name = "engineeringReadOnlyDataSource", destroyMethod = "close")
    public HikariDataSource engineeringReadOnlyDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName(driver);
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(maximumPoolSize);
        ds.setMinimumIdle(0);
        ds.setPoolName("EngineeringReadOnly");
        // Belt as well as braces. The role cannot write in any case; this also makes
        // an accidental write fail in the driver, with a clearer message than a
        // permission error buried in a scan log.
        ds.setReadOnly(true);
        ds.setConnectionTimeout(20_000);
        ds.setIdleTimeout(60_000);
        // Nothing connects at startup: minimumIdle 0 plus lazy initialisation means a
        // missing role does not prevent the application from booting, only from
        // scanning.
        ds.setInitializationFailTimeout(-1);
        log.info("Engineering read-only datasource configured: user={} pool={}", username, maximumPoolSize);
        return ds;
    }

    /**
     * The application's ordinary JdbcTemplate, declared explicitly.
     *
     * <p>It has to be, because of the bean below. Spring Boot auto-configures a
     * {@code JdbcTemplate} only {@code @ConditionalOnMissingBean}, so the moment
     * this class declares one of that type Boot backs off entirely — and every
     * {@code @Autowired JdbcTemplate} in the application silently resolves to the
     * read-only one instead.
     *
     * <p>That is not a theoretical risk: it happened. The full suite came back with
     * 178 errors reading {@code ERROR: permission denied for table users}, because
     * the whole application had quietly become read-only. Declaring the primary
     * template restores it, and {@code @Primary} is what makes an unqualified
     * injection point pick it.
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** The only JDBC handle the anomaly rules are given. Never injected unqualified. */
    @Bean(name = "engineeringReadOnlyJdbc")
    public JdbcTemplate engineeringReadOnlyJdbc(
            @Qualifier("engineeringReadOnlyDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
