package net.ximatai.muyun.spring.platform.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Lightweight source guards for the stable static-module extension surface. */
class PlatformArchitectureBoundaryTest {
    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Map<String, String> WEB_MODULE_DOMAINS = Map.of(
            "muyun-platform-web", "platform",
            "muyun-iam-web", "iam",
            "muyun-dynamic-web", "dynamic",
            "muyun-demo-web", "demo"
    );
    private static final Pattern WEB_IMPORT = Pattern.compile(
            "(?m)^import\\s+net\\.ximatai\\.muyun\\.spring\\.([a-z][a-z0-9_]*)\\.web\\.");
    private static final Pattern FRONTEND_DELIVERY_FACT = Pattern.compile(
            "(?i)(?:\\.vue|\\bvue\\b|frontend|componentName|vueComponent|modulePath)");

    @Test
    void webDeliveryModulesDoNotImportPeerDomainWebProductionPackages() throws IOException {
        for (Map.Entry<String, String> module : WEB_MODULE_DOMAINS.entrySet()) {
            Path sourceRoot = REPOSITORY_ROOT.resolve(module.getKey()).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) continue;
            for (Path source : javaSources(sourceRoot)) {
                String content = Files.readString(source);
                var matcher = WEB_IMPORT.matcher(content);
                while (matcher.find()) {
                    String importedDomain = matcher.group(1);
                    assertThat(importedDomain)
                            .as("%s must not import peer web delivery package", source)
                            .isIn(module.getValue(), "platform", "common");
                }
            }
        }
    }

    @Test
    void descriptorsAndCompilersDoNotCarryFrontendImplementationFacts() throws IOException {
        Path sourceRoot = Path.of("src/main/java/net/ximatai/muyun/spring/platform/web");
        for (Path source : javaSources(sourceRoot)) {
            String filename = source.getFileName().toString();
            if (!filename.contains("Descriptor") && !filename.contains("Compiler")) continue;
            assertThat(FRONTEND_DELIVERY_FACT.matcher(Files.readString(source)).find())
                    .as("%s must expose renderer facts, not frontend implementation details", source)
                    .isFalse();
        }
    }

    @Test
    void legacyReadProjectionMarkerHasOneExplicitConsumerAndRemovalChecklist() throws IOException {
        List<Path> consumers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPOSITORY_ROOT)) {
            for (Path path : files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/src/test/"))
                    .toList()) {
                if (Files.readString(path).contains("LegacyStaticReadProjectionCompatibility")) {
                    consumers.add(path);
                }
            }
        }
        assertThat(consumers.stream()
                .map(path -> REPOSITORY_ROOT.relativize(path).toString().replace('\\', '/'))
                .toList())
                .containsExactlyInAnyOrder(
                        "muyun-platform-web/src/main/java/net/ximatai/muyun/spring/platform/web/CrudWeb.java",
                        "muyun-platform-web/src/main/java/net/ximatai/muyun/spring/platform/web/LegacyStaticReadProjectionCompatibility.java",
                        "muyun-platform-web/src/main/java/net/ximatai/muyun/spring/platform/web/StaticModuleDefinitionScanner.java",
                        "muyun-iam-web/src/main/java/net/ximatai/muyun/spring/iam/web/EmployeeWebController.java");
        assertThat(Files.readString(Path.of("../docs/TECHNICAL_DEBT.md")))
                .contains("| TD-049 | 职员模块仍依赖静态读投影兼容路径 |")
                .contains("LegacyStaticReadProjectionCompatibility")
                .contains("计划缺失启动失败、请求期不重解 DSL");
    }

    private List<Path> javaSources(Path sourceRoot) throws IOException {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
