package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class LowCodeModulePackageHealthChecker implements LowCodeModuleHealthChecker {
    private final LowCodeModulePackageValidator validator;

    public LowCodeModulePackageHealthChecker(LowCodeModulePackageValidator validator) {
        this.validator = validator == null ? new LowCodeModulePackageValidator() : validator;
    }

    @Autowired
    public LowCodeModulePackageHealthChecker() {
        this(null);
    }

    @Override
    public List<LowCodeConfigHealthItem> check(LowCodeModuleHealthContext context) {
        LowCodeModulePackage modulePackage = context == null ? null : context.modulePackage();
        if (modulePackage == null) {
            return List.of();
        }
        if (!Objects.equals(context.moduleAlias(), modulePackage.moduleAlias())) {
            return List.of(LowCodeConfigHealthItem.error(LowCodeConfigHealthScope.PACKAGE,
                "PACKAGE_MODULE_MISMATCH",
                DiagnosticText.code("platform.config.diagnostic.package-modulealias-does-not-match-health-context",
                    "package moduleAlias does not match health context"),
                "module",
                modulePackage.moduleAlias(),
                DiagnosticText.code("platform.config.diagnostic.check-the-target-module-before-importing-or-archiving-this-package",
                    "Check the target module before importing or archiving this package")));
        }
        try {
            validator.validate(modulePackage);
            return List.of();
        } catch (IllegalArgumentException ex) {
            DiagnosticText suggestion = DiagnosticText.code("platform.config.diagnostic.fix-the-package-structure-before-running-archive-migration-or-template-operations",
                    "Fix the package structure before running archive, migration or template operations");
            var declared = ex instanceof net.ximatai.muyun.spring.common.i18n.TextSourceAware source ? source : null;
            return List.of(new LowCodeConfigHealthItem(LowCodeConfigHealthScope.PACKAGE, "PACKAGE_INVALID",
                    LowCodeConfigHealthSeverity.ERROR, ex.getMessage(), "package", modulePackage.moduleAlias(),
                    suggestion.message(), declared == null ? null : declared.textSource(),
                    declared == null ? java.util.Map.of() : declared.messageArgs(),
                    suggestion.textSource(), suggestion.messageArgs()));
        }
    }
}
