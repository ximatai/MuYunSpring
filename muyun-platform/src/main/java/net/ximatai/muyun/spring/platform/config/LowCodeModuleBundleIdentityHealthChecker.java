package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LowCodeModuleBundleIdentityHealthChecker implements LowCodeModuleHealthChecker {
    @Override
    public List<LowCodeConfigHealthItem> check(LowCodeModuleHealthContext context) {
        LowCodeModulePackage modulePackage = context == null ? null : context.modulePackage();
        if (modulePackage == null) {
            return List.of();
        }
        List<LowCodeConfigHealthItem> items = new ArrayList<>();
        for (LowCodeConfigBundle bundle : modulePackage.bundles()) {
            if (bundle == null || !bundle.included()) {
                continue;
            }
            checkIdentity(items, modulePackage, bundle, "module");
            checkIdentity(items, modulePackage, bundle, "moduleAlias");
        }
        return items;
    }

    private void checkIdentity(List<LowCodeConfigHealthItem> items,
                               LowCodeModulePackage modulePackage,
                               LowCodeConfigBundle bundle,
                               String key) {
        Map<String, Object> content = bundle.content();
        Object value = content.get(key);
        if (value == null || modulePackage.moduleAlias().equals(value)) {
            return;
        }
        items.add(LowCodeConfigHealthItem.error(scope(bundle.type()),
                "BUNDLE_MODULE_IDENTITY_MISMATCH",
                DiagnosticText.code("platform.config.diagnostic.bundle-does-not-match-package-modulealias",
                    "bundle {key} does not match package moduleAlias", Map.of("key", String.valueOf(key))),
                "bundle",
                bundle.type().name(),
                DiagnosticText.code("platform.config.diagnostic.keep-bundle-top-level-module-identity-aligned-with-package-modulealias",
                    "Keep bundle top-level module identity aligned with package moduleAlias")));
    }

    private LowCodeConfigHealthScope scope(LowCodePackageBundleType type) {
        return switch (type) {
            case METADATA -> LowCodeConfigHealthScope.METADATA;
            case PAGE -> LowCodeConfigHealthScope.PAGE;
            case INTERACTION -> LowCodeConfigHealthScope.INTERACTION;
            case ENTRY -> LowCodeConfigHealthScope.ENTRY;
            case AUTOMATION -> LowCodeConfigHealthScope.AUTOMATION;
        };
    }
}
