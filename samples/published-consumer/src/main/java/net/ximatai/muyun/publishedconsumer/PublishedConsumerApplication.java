package net.ximatai.muyun.publishedconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Isolated consumer used to verify Maven metadata and Starter auto-configuration. */
@SpringBootApplication
public class PublishedConsumerApplication {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Profile("smoke")
    org.springframework.boot.ApplicationRunner verifyDeployedLanguageResources(
            net.ximatai.muyun.spring.common.i18n.LanguageCatalog catalog,
            net.ximatai.muyun.spring.common.i18n.TextResolver texts) {
        return arguments -> {
            if (!"fr".equals(catalog.select("fr-CA"))) throw new IllegalStateException("Third locale was not enabled");
            var source = net.ximatai.muyun.spring.common.i18n.TextReference.code("consumer.smoke.greeting", "Hello {name}");
            try (var scope = net.ximatai.muyun.spring.common.i18n.LanguageContext.use("fr")) {
                var result = texts.resolve(source, java.util.Map.of("name", "用户原名"));
                if (!"Bonjour 用户原名".equals(result.text()) || !"fr".equals(result.resourceTag()))
                    throw new IllegalStateException("Owning application resources were not resolved");
            }
            if (net.ximatai.muyun.spring.common.i18n.LanguageContext.currentTag() != null)
                throw new IllegalStateException("Consumer language scope leaked");
            System.out.println("Published consumer localization verified");
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(PublishedConsumerApplication.class, args);
    }
}
