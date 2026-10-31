package com.careerpilot.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;

/**
 * Chooses the database. Order: spring.datasource.url (tests, system properties), DATABASE_URL from .env or the environment,
 * then an H2 file in ~/.careerpilot shared by the GUI, CLI and server. A blank value counts as "not set", so a placeholder
 * line such as {@code DATABASE_URL=} in .env can never silently switch the app to a throw-away in-memory database.
 */
@Configuration
public class DataSourceConfig {
    static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.strip();
        return null;
    }

    @Bean
    public DataSource dataSource(Environment env) {
        String home = System.getProperty("user.home");
        String url = firstNonBlank(env.getProperty("spring.datasource.url"), env.getProperty("DATABASE_URL"),
                "jdbc:h2:file:" + home + "/.careerpilot/careerpilot;AUTO_SERVER=TRUE");
        String user = firstNonBlank(env.getProperty("spring.datasource.username"), env.getProperty("DATABASE_USER"), "sa");
        String password = firstNonBlank(env.getProperty("spring.datasource.password"), env.getProperty("DATABASE_PASSWORD"), "");
        return DataSourceBuilder.create().url(url).username(user).password(password).build();
    }
}
