package com.thatsme4now.depot.config;
 
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
 
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.Properties;
 
@Configuration
@EnableTransactionManagement
public class DatabaseConfig {
 
    private final Environment env;
 
    public DatabaseConfig(Environment env) {
        this.env = env;
    }
 
    /** Returns the configured db mode: "inmemory", "h2file", or "mysql". */
    private String dbMode() {
        return env.getProperty("depot.db", "inmemory").trim().toLowerCase();
    }
 
    private boolean isH2() {
        String mode = dbMode();
        return "inmemory".equals(mode) || "h2file".equals(mode);
    }
 
    @Bean
    @Primary
    public DataSource dataSource() {
        return switch (dbMode()) {
            case "inmemory" -> DataSourceBuilder.create()
                .url("jdbc:h2:mem:depot;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;MODE=MySQL;NON_KEYWORDS=VALUE")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
 
            case "h2file" -> DataSourceBuilder.create()
                // File stored next to the JAR as depot-data.mv.db
                // AUTO_SERVER=TRUE allows multiple connections (e.g. external tool)
                .url("jdbc:h2:file:./depot-data;MODE=MySQL;NON_KEYWORDS=VALUE;AUTO_SERVER=TRUE")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
 
            default -> DataSourceBuilder.create()  // mysql
                .url(env.getProperty("spring.datasource.url"))
                .username(env.getProperty("spring.datasource.username"))
                .password(env.getProperty("spring.datasource.password"))
                .driverClassName(env.getProperty("spring.datasource.driver-class-name"))
                .build();
        };
    }
 
    @Bean
    public DataSourceInitializer dataSourceInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
 
        if (isH2()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("schema-h2.sql"));
            // For h2file: CREATE TABLE IF NOT EXISTS ensures no data loss on restart
            populator.setContinueOnError(false);
            populator.setSqlScriptEncoding("UTF-8");
            initializer.setDatabasePopulator(populator);
        }
 
        return initializer;
    }
 
    @Bean
    @Primary
    @DependsOn("dataSourceInitializer")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.thatsme4now.depot.entity");
 
        JpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(adapter);
 
        Properties props = new Properties();
        props.setProperty("hibernate.hbm2ddl.auto", "none");
        props.setProperty("hibernate.show_sql",   env.getProperty("spring.jpa.show-sql", "false"));
        props.setProperty("hibernate.format_sql", "false");
        if (!isH2()) {
            props.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        }
        em.setJpaProperties(props);
        return em;
    }
 
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}