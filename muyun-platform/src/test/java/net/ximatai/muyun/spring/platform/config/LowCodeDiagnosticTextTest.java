package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static net.ximatai.muyun.spring.platform.config.LowCodeConfigTestFixtures.*;

class LowCodeDiagnosticTextTest {
    @Test
    void dependencyFactsRetainSourcesThroughHealthAndImportConflictProjection() {
        var modulePackage = fullPackage("crm.contract", List.of(LowCodePackageDependency.module("crm.customer")));
        var diagnostic = new LowCodePackageDependencyDiagnostics(List.of()).diagnose(modulePackage).getFirst();
        var item = new LowCodeModuleDependencyHealthChecker().check(LowCodeModuleHealthContext.ofPackage(modulePackage)).getFirst();
        var exchange = new LowCodeModulePackageExchangeService(
                new LowCodeModuleConfigVersionService(new TestMemoryDao<>()),
                new LowCodeModuleHealthService(List.of(new LowCodeModuleDependencyHealthChecker())));
        var result = exchange.dryRunImport(modulePackage);
        var conflict = result.conflicts().getFirst();
        assertThat(result.blocked()).isTrue();
        assertThat(item.code()).isEqualTo(diagnostic.conflictType().name());
        assertThat(item.targetId()).isEqualTo("crm.customer");
        assertThat(item.severity()).isEqualTo(LowCodeConfigHealthSeverity.ERROR);
        assertThat(item.textSource()).isEqualTo(diagnostic.textSource()).isEqualTo(conflict.textSource());
        assertThat(item.messageArgs()).isEqualTo(diagnostic.messageArgs()).isEqualTo(conflict.messageArgs());
        assertThat(item.message()).isEqualTo(diagnostic.message()).isEqualTo(conflict.message());
        var loader = getClass().getClassLoader();
        var resources = TextResources.load(loader, LanguageCatalog.load(loader, "zh-CN"));
        assertThat(resources.resolve(item.textSource(), item.messageArgs(), "zh-CN").text()).isEqualTo("必需的 MODULE 依赖没有可用解析器");
        assertThat(resources.resolve(item.textSource(), item.messageArgs(), "en").text()).isEqualTo(item.message());
        assertThat(item.suggestionSource()).isNotNull();
    }

    @Test
    void packageValidationRetainsDeclarationsAndExternalDiagnosticsRemainLiteral() {
        var invalid = new LowCodeModulePackage("m10.v1", LowCodePackageMode.PAGE_ONLY, "crm", "crm.contract",
                List.of(LowCodeConfigBundle.included(LowCodePackageBundleType.METADATA, Map.of("module", "crm.contract"))), null, null);
        var item = new LowCodeModulePackageHealthChecker().check(LowCodeModuleHealthContext.ofPackage(invalid)).getFirst();
        assertThat(item.code()).isEqualTo("PACKAGE_INVALID");
        assertThat(item.messageArgs()).containsEntry("type", "METADATA");
        assertThat(item.textSource()).isNotNull();
        var external = new LowCodeModulePackageValidator() {
            @Override public void validate(LowCodeModulePackage value) {
                throw new IllegalArgumentException("external {type}");
            }
        };
        var literal = new LowCodeModulePackageHealthChecker(external).check(LowCodeModuleHealthContext.ofPackage(invalid)).getFirst();
        assertThat(literal.message()).isEqualTo("external {type}");
        assertThat(literal.textSource()).isNull();
        assertThat(literal.messageArgs()).isEmpty();
    }
}
