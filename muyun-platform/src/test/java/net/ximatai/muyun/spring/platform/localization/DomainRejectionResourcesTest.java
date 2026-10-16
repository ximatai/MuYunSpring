package net.ximatai.muyun.spring.platform.localization;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DomainRejectionResourcesTest {
    private static final Pattern ARGUMENT = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.-]*)}");

    @Test
    void deployedRejectionsHaveBothLanguagesAndKeepTheSameArguments() throws Exception {
        var chinese = messages("zh-CN");
        var english = messages("en");
        assertThat(chinese).isNotEmpty();
        assertThat(english.keySet()).containsExactlyInAnyOrderElementsOf(chinese.keySet());
        for (var entry : chinese.entrySet()) {
            String key = entry.getKey();
            assertThat(english.get(key)).as(key).isNotBlank().doesNotContainPattern("[\\p{IsHan}]");
            assertThat(arguments(english.get(key))).as(key).isEqualTo(arguments(entry.getValue()));
        }
    }

    private Map<String, String> messages(String tag) throws Exception {
        var result = new LinkedHashMap<String, String>();
        var urls = getClass().getClassLoader().getResources("META-INF/muyun/i18n/messages_" + tag + ".properties");
        while (urls.hasMoreElements()) {
            var properties = new Properties();
            try (var reader = new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("platform.rejection.") || key.startsWith("iam.rejection.")) result.put(key, properties.getProperty(key));
            }
        }
        return result;
    }

    private Set<String> arguments(String message) {
        return ARGUMENT.matcher(message).results().map(match -> match.group(1)).collect(Collectors.toSet());
    }
}
