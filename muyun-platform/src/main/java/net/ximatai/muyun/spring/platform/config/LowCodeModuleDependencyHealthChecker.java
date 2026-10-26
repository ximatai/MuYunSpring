package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LowCodeModuleDependencyHealthChecker implements LowCodeModuleHealthChecker {
    private final LowCodePackageDependencyDiagnostics dependencyDiagnostics;

    public LowCodeModuleDependencyHealthChecker() {
        this(List.of());
    }

    @Autowired
    public LowCodeModuleDependencyHealthChecker(List<LowCodePackageDependencyResolver> dependencyResolvers) {
        this.dependencyDiagnostics = new LowCodePackageDependencyDiagnostics(dependencyResolvers);
    }

    @Override
    public List<LowCodeConfigHealthItem> check(LowCodeModuleHealthContext context) {
        LowCodeModulePackage modulePackage = context == null ? null : context.modulePackage();
        if (modulePackage == null) {
            return List.of();
        }
        List<LowCodeConfigHealthItem> items = new ArrayList<>();
        for (LowCodePackageDependencyDiagnostic diagnostic : dependencyDiagnostics.diagnose(modulePackage)) {
            items.add(item(diagnostic));
        }
        return items;
    }

    private LowCodeConfigHealthItem item(LowCodePackageDependencyDiagnostic diagnostic) {
        DiagnosticText suggestion = suggestion(diagnostic);
        return new LowCodeConfigHealthItem(LowCodeConfigHealthScope.DEPENDENCY, diagnostic.conflictType().name(),
                diagnostic.blocking() ? LowCodeConfigHealthSeverity.ERROR : LowCodeConfigHealthSeverity.WARN,
                diagnostic.message(), diagnostic.dependency().type().name(), diagnostic.targetId(), suggestion.message(),
                diagnostic.textSource(), diagnostic.messageArgs(), suggestion.textSource(), suggestion.messageArgs());
    }

    private DiagnosticText suggestion(LowCodePackageDependencyDiagnostic diagnostic) {
        if (diagnostic.conflictType() == LowCodePackageConflictType.DEPENDENCY_RESOLVER_MISSING) {
            return DiagnosticText.code("platform.config.diagnostic.add-a-resolver-or-mark-this-dependency-as-manifest-only-for-migration-diagnostics",
                    "Add a resolver or mark this dependency as manifest-only for migration diagnostics");
        }
        return DiagnosticText.code("platform.config.diagnostic.create-the-dependency-in-the-target-environment-or-update-dependencymanifest",
                    "Create the dependency in the target environment or update dependencyManifest");
    }
}
