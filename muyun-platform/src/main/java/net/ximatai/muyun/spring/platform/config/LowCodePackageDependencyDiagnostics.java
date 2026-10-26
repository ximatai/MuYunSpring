package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LowCodePackageDependencyDiagnostics {
    private final List<LowCodePackageDependencyResolver> dependencyResolvers;

    public LowCodePackageDependencyDiagnostics(List<LowCodePackageDependencyResolver> dependencyResolvers) {
        this.dependencyResolvers = dependencyResolvers == null ? List.of() : List.copyOf(dependencyResolvers);
    }

    public List<LowCodePackageDependencyDiagnostic> diagnose(LowCodeModulePackage modulePackage) {
        if (modulePackage == null) {
            return List.of();
        }
        List<LowCodePackageDependencyDiagnostic> diagnostics = new ArrayList<>();
        for (LowCodePackageDependency dependency : modulePackage.dependencyManifest().dependencies()) {
            if (dependency == null) {
                continue;
            }
            LowCodePackageDependencyResolver resolver = resolver(dependency.type());
            if (resolver == null) {
                diagnostics.add(missingResolver(dependency));
                continue;
            }
            if (!resolver.exists(dependency)) {
                diagnostics.add(missingDependency(dependency));
            }
        }
        return diagnostics;
    }

    private LowCodePackageDependencyDiagnostic missingResolver(LowCodePackageDependency dependency) {
        return new LowCodePackageDependencyDiagnostic(dependency,
                LowCodePackageConflictType.DEPENDENCY_RESOLVER_MISSING,
                dependency.type().platformResolvedByDefault() && dependency.required(),
                targetId(dependency),
                dependency.required() ? DiagnosticText.code("platform.config.diagnostic.no-dependency-resolver-is-available-for-required-dependency",
                    "No dependency resolver is available for required {type} dependency", Map.of("type", String.valueOf(dependency.type()))) : DiagnosticText.code("platform.config.diagnostic.no-dependency-resolver-is-available-for-optional-dependency",
                    "No dependency resolver is available for optional {type} dependency", Map.of("type", String.valueOf(dependency.type()))));
    }

    private LowCodePackageDependencyDiagnostic missingDependency(LowCodePackageDependency dependency) {
        return new LowCodePackageDependencyDiagnostic(dependency,
                dependency.required()
                        ? LowCodePackageConflictType.REQUIRED_DEPENDENCY_MISSING
                        : LowCodePackageConflictType.OPTIONAL_DEPENDENCY_MISSING,
                dependency.required(),
                targetId(dependency),
                DiagnosticText.code("platform.config.diagnostic.package-dependency-is-missing",
                    "Package dependency is missing: {type}", Map.of("type", String.valueOf(dependency.type()))));
    }

    private LowCodePackageDependencyResolver resolver(LowCodePackageDependencyType type) {
        return dependencyResolvers.stream()
                .filter(resolver -> resolver.supports(type))
                .findFirst()
                .orElse(null);
    }

    private String targetId(LowCodePackageDependency dependency) {
        return switch (dependency.type()) {
            case MODULE -> dependency.moduleAlias();
            case ACTION -> dependency.moduleAlias() + ":" + dependency.alias();
            case DICTIONARY, MEASURE_UNIT -> dependency.applicationAlias() + ":" + dependency.alias();
            case CURRENCY, EXCHANGE_RATE_TYPE, WORKFLOW, FILE_SERVICE, EXTERNAL -> dependency.alias();
        };
    }

}
