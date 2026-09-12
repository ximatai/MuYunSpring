package net.ximatai.muyun.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingPatternConfigurationTest {
    @Test
    void shouldConfigureStableMdcPlaceholdersForConsoleAndFileLogbackOutput() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();

        assertThat(properties.getProperty("logging.pattern.console"))
                .contains("%X{traceId:-}", "%X{endpointId:-}", "%X{moduleAlias:-}", "%X{actionCode:-}");
        assertThat(properties.getProperty("logging.pattern.file"))
                .contains("%X{traceId:-}", "%X{endpointId:-}", "%X{moduleAlias:-}", "%X{actionCode:-}");
    }
}
