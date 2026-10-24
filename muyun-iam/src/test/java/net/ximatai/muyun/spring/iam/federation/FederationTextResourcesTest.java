package net.ximatai.muyun.spring.iam.federation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every text code and enum the federation package shows to users must be translated, not left to fallbacks. */
class FederationTextResourcesTest {
    private static final Pattern TEXT_CODE = Pattern.compile("TextReference\\.code\\(\\s*\"([^\"]+)\"");
    private static final Path SOURCES = Path.of("src/main/java/net/ximatai/muyun/spring/iam/federation");

    @Test
    void registersEveryFederationTextCodeInBothLanguages() throws IOException {
        Set<String> codes = new TreeSet<>();
        try (Stream<Path> files = Files.list(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                TEXT_CODE.matcher(Files.readString(file)).results().forEach(match -> codes.add(match.group(1)));
            }
        }

        assertThat(codes).isNotEmpty();
        assertThat(messages("zh-CN").stringPropertyNames()).containsAll(codes);
        assertThat(messages("en").stringPropertyNames()).containsAll(codes);
    }

    @Test
    void titlesEveryFederationEnumInEnglish() throws IOException {
        Properties english = messages("en");
        for (Class<? extends Enum<?>> type : java.util.List.<Class<? extends Enum<?>>>of(ProvisioningMode.class,
                ExternalIdentityBindingSource.class, ExternalLoginStatus.class, ExternalLoginPurpose.class,
                ExternalLoginFailure.class)) {
            Arrays.stream(type.getEnumConstants()).forEach(constant -> assertThat(english.getProperty(
                    "enum/" + type.getName() + "/"
                            + ((net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum) constant).getCode()
                            + "/title")).as(constant.name()).isNotBlank());
        }
    }

    private Properties messages(String tag) throws IOException {
        Properties properties = new Properties();
        var url = getClass().getClassLoader().getResource("META-INF/muyun/i18n/messages_" + tag + ".properties");
        assertThat(url).isNotNull();
        try (var reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
