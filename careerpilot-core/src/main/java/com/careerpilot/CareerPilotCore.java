package com.careerpilot;

import com.careerpilot.common.AppProperties;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Root configuration of the shared core. The REST server, the CLI and the JavaFX GUI each start a Spring
 * context from this class (the server in web mode, the other two headless), so all three use the same services.
 */
@Configuration
@EnableAutoConfiguration
@ComponentScan("com.careerpilot")
@EntityScan("com.careerpilot")
@EnableJpaRepositories("com.careerpilot")
@EnableConfigurationProperties(AppProperties.class)
public class CareerPilotCore {
}
