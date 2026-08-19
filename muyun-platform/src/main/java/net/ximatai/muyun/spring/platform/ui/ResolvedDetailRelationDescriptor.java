package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/**
 * Source-neutral detail relation contract.  Both static declarations and dynamic association
 * views resolve here; only a non-null query contract is executable by a relation-list runtime.
 */
public record ResolvedDetailRelationDescriptor(
        String code,
        String title,
        boolean readOnly,
        String sourceModuleAlias,
        String sourceEntityAlias,
        String targetModuleAlias,
        String targetEntityAlias,
        String parentBinding,
        ResolvedDetailRelationQueryContract queryContract,
        boolean refreshOnDetailReload
) {
    public ResolvedDetailRelationDescriptor {
        code = PlatformNameRules.requireIdentifier(code, "detail relation code");
        title = normalize(title);
        sourceModuleAlias = PlatformNameRules.requireModuleAlias(sourceModuleAlias);
        sourceEntityAlias = PlatformNameRules.requireIdentifier(sourceEntityAlias, "source entity alias");
        targetModuleAlias = PlatformNameRules.requireModuleAlias(targetModuleAlias);
        targetEntityAlias = PlatformNameRules.requireIdentifier(targetEntityAlias, "target entity alias");
        parentBinding = requireText(parentBinding, "detail relation parent binding");
    }

    public boolean hasExecutableQueryContract() {
        return queryContract != null;
    }

    public ResolvedDetailRelationDescriptor withTitle(String value) {
        return new ResolvedDetailRelationDescriptor(code, value, readOnly, sourceModuleAlias, sourceEntityAlias,
                targetModuleAlias, targetEntityAlias, parentBinding, queryContract, refreshOnDetailReload);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
