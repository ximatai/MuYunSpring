package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.exception.DeclaredArgumentException;
import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public class LowCodeModulePackageValidator {
    private static final Set<LowCodePackageBundleType> PAGE_ONLY_ALLOWED = EnumSet.of(
            LowCodePackageBundleType.PAGE,
            LowCodePackageBundleType.INTERACTION,
            LowCodePackageBundleType.ENTRY
    );

    public void validate(LowCodeModulePackage modulePackage) {
        if (modulePackage == null) {
            throw new DeclaredArgumentException(DiagnosticText.code("platform.config.diagnostic.low-code-module-package-must-not-be-null",
                    "low code module package must not be null"));
        }
        Map<LowCodePackageBundleType, LowCodeConfigBundle> bundles = requireUniqueBundles(modulePackage);
        requireIncludedBundlesHaveContent(bundles);
        validateMode(modulePackage.mode(), bundles);
        validateDependencies(modulePackage);
    }

    private Map<LowCodePackageBundleType, LowCodeConfigBundle> requireUniqueBundles(LowCodeModulePackage modulePackage) {
        Map<LowCodePackageBundleType, LowCodeConfigBundle> result = new EnumMap<>(LowCodePackageBundleType.class);
        for (LowCodeConfigBundle bundle : modulePackage.bundles()) {
            if (bundle == null) {
                throw new DeclaredArgumentException(DiagnosticText.code("platform.config.diagnostic.package-bundle-must-not-be-null",
                    "package bundle must not be null"));
            }
            if (result.put(bundle.type(), bundle) != null) {
                throw new DeclaredArgumentException(DiagnosticText.code("platform.config.diagnostic.duplicated-package-bundle",
                    "duplicated package bundle: {type}", Map.of("type", String.valueOf(bundle.type()))));
            }
        }
        return result;
    }

    private void requireIncludedBundlesHaveContent(Map<LowCodePackageBundleType, LowCodeConfigBundle> bundles) {
        for (LowCodeConfigBundle bundle : bundles.values()) {
            if (bundle.included() && bundle.content().isEmpty()) {
                throw new DeclaredArgumentException(DiagnosticText.code("platform.config.diagnostic.included-package-bundle-must-not-be-empty",
                    "included package bundle must not be empty: {type}", Map.of("type", String.valueOf(bundle.type()))));
            }
        }
    }

    private void validateMode(LowCodePackageMode mode,
                              Map<LowCodePackageBundleType, LowCodeConfigBundle> bundles) {
        if (mode == LowCodePackageMode.PAGE_ONLY) {
            rejectUnexpectedPageOnlyBundles(bundles);
            requireIncluded(bundles, LowCodePackageBundleType.PAGE, DiagnosticText.code("platform.config.diagnostic.page-only-package-must-include-page-bundle",
                    "PAGE_ONLY package must include PAGE bundle"));
            return;
        }
        requireIncluded(bundles, LowCodePackageBundleType.METADATA, DiagnosticText.code("platform.config.diagnostic.package-must-include-metadata-bundle",
                    "{mode} package must include METADATA bundle", Map.of("mode", String.valueOf(mode))));
    }

    private void rejectUnexpectedPageOnlyBundles(Map<LowCodePackageBundleType, LowCodeConfigBundle> bundles) {
        for (LowCodeConfigBundle bundle : bundles.values()) {
            if (bundle.included() && !PAGE_ONLY_ALLOWED.contains(bundle.type())) {
                throw new DeclaredArgumentException(DiagnosticText.code("platform.config.diagnostic.page-only-package-cannot-include-bundle",
                    "PAGE_ONLY package cannot include {type} bundle", Map.of("type", String.valueOf(bundle.type()))));
            }
        }
    }

    private void requireIncluded(Map<LowCodePackageBundleType, LowCodeConfigBundle> bundles, LowCodePackageBundleType type, DiagnosticText message) {
        LowCodeConfigBundle bundle = bundles.get(type);
        if (bundle == null || !bundle.included()) {
            throw new DeclaredArgumentException(message);
        }
    }

    private void validateDependencies(LowCodeModulePackage modulePackage) {
        for (LowCodePackageDependency dependency : modulePackage.dependencyManifest().dependencies()) {
            if (dependency == null) {
                throw new DeclaredArgumentException(DiagnosticText.code("platform.config.diagnostic.package-dependency-must-not-be-null",
                    "package dependency must not be null"));
            }
            switch (dependency.type()) {
                case MODULE -> requireText(dependency.moduleAlias(), "MODULE dependency moduleAlias");
                case ACTION -> {
                    requireText(dependency.moduleAlias(), "ACTION dependency moduleAlias");
                    requireText(dependency.alias(), "ACTION dependency actionCode");
                }
                case DICTIONARY -> {
                    requireText(dependency.applicationAlias(), "DICTIONARY dependency applicationAlias");
                    requireText(dependency.alias(), "DICTIONARY dependency alias");
                }
                case MEASURE_UNIT -> {
                    requireText(dependency.applicationAlias(), "MEASURE_UNIT dependency applicationAlias");
                    requireText(dependency.alias(), "MEASURE_UNIT dependency alias");
                }
                case CURRENCY -> requireText(dependency.alias(), "CURRENCY dependency currencyCode");
                case EXCHANGE_RATE_TYPE -> requireText(dependency.alias(),
                        "EXCHANGE_RATE_TYPE dependency rateTypeCode");
                case WORKFLOW, FILE_SERVICE, EXTERNAL -> requireText(dependency.alias(), dependency.type() + " dependency alias");
                default -> throw new DeclaredArgumentException(DiagnosticText.code("platform.config.diagnostic.unsupported-dependency-type",
                    "unsupported dependency type: {type}", Map.of("type", String.valueOf(dependency.type()))));
            }
        }
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DeclaredArgumentException(DiagnosticText.code("platform.config.diagnostic.must-not-be-blank",
                    "{name} must not be blank", Map.of("name", String.valueOf(name))));
        }
    }
}
