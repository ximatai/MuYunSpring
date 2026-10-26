package net.ximatai.muyun.spring.platform.web;

import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationDiagnosticResourcesTest {
    private static final Pattern DECLARATION = Pattern.compile(
            "DiagnosticText\\.code\\(\\s*\"([^\"]+)\",\\s*\"([^\"]*)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    @Test
    void everyProducerDeclarationHasBilingualResourcesWithTheSameLiteralPlaceholders() throws Exception {
        var en = resources("en");
        var zh = resources("zh-CN");
        int declarations = 0;
        for (String module : new String[]{"muyun-platform", "muyun-platform-web", "muyun-dynamic"}) {
            try (var paths = Files.walk(Path.of("..", module, "src/main/java"))) {
                for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher = DECLARATION.matcher(Files.readString(path));
                    while (matcher.find()) {
                        var key = matcher.group(1);
                        if (!key.contains(".diagnostic.")) continue;
                        var original = matcher.group(2);
                        assertThat(en).as("English declaration at %s", path).containsEntry(key, original);
                        assertThat(zh.get(key)).as(key).isNotBlank();
                        assertThat(placeholders(zh.get(key))).as(key).isEqualTo(placeholders(original));
                        declarations++;
                    }
                }
            }
        }
        assertThat(declarations).isGreaterThan(50);
    }

    private Map<String, String> resources(String tag) throws Exception {
        Map<String, String> output = new HashMap<>();
        var urls = getClass().getClassLoader().getResources("META-INF/muyun/i18n/messages_" + tag + ".properties");
        while (urls.hasMoreElements()) {
            try (var reader = new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8)) {
                var properties = new Properties();
                properties.load(reader);
                properties.forEach((key, value) -> output.put(key.toString(), value.toString()));
            }
        }
        return output;
    }

    private Set<String> placeholders(String text) {
        Set<String> names = new HashSet<>();
        var matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }
}
