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
        ResolvedDetailRelationMutationContract mutationContract,
        ResolvedDetailRelationParentConstraint parentConstraint,
        ResolvedDetailRelationEditing editing,
        boolean refreshOnDetailReload,
        String embeddedField,
        ResolvedDetailRelationListProjection listProjection,
        ResolvedUiRule<Boolean> visible
) {
    /** Source-compatible constructor for read-only relations. */
    public ResolvedDetailRelationDescriptor(String code, String title, boolean readOnly,
                                            String sourceModuleAlias, String sourceEntityAlias,
                                            String targetModuleAlias, String targetEntityAlias,
                                            String parentBinding,
                                            ResolvedDetailRelationQueryContract queryContract,
                                            boolean refreshOnDetailReload) {
        this(code, title, readOnly, sourceModuleAlias, sourceEntityAlias, targetModuleAlias,
                targetEntityAlias, parentBinding, queryContract, null, null,
                ResolvedDetailRelationEditing.DEFAULT, refreshOnDetailReload, null, null,
                ResolvedUiRule.constant(Boolean.TRUE));
    }

    /** Source-compatible constructor for mutable relations before parent applicability was explicit. */
    public ResolvedDetailRelationDescriptor(String code, String title, boolean readOnly,
                                            String sourceModuleAlias, String sourceEntityAlias,
                                            String targetModuleAlias, String targetEntityAlias,
                                            String parentBinding,
                                            ResolvedDetailRelationQueryContract queryContract,
                                            ResolvedDetailRelationMutationContract mutationContract,
                                            boolean refreshOnDetailReload) {
        this(code, title, readOnly, sourceModuleAlias, sourceEntityAlias, targetModuleAlias,
                targetEntityAlias, parentBinding, queryContract, mutationContract, null,
                ResolvedDetailRelationEditing.DEFAULT, refreshOnDetailReload, null, null,
                ResolvedUiRule.constant(Boolean.TRUE));
    }

    /** Source-compatible constructor before editing semantics became explicit. */
    public ResolvedDetailRelationDescriptor(String code, String title, boolean readOnly,
                                            String sourceModuleAlias, String sourceEntityAlias,
                                            String targetModuleAlias, String targetEntityAlias,
                                            String parentBinding,
                                            ResolvedDetailRelationQueryContract queryContract,
                                            ResolvedDetailRelationMutationContract mutationContract,
                                            ResolvedDetailRelationParentConstraint parentConstraint,
                                            boolean refreshOnDetailReload) {
        this(code, title, readOnly, sourceModuleAlias, sourceEntityAlias, targetModuleAlias,
                targetEntityAlias, parentBinding, queryContract, mutationContract, parentConstraint,
                ResolvedDetailRelationEditing.DEFAULT, refreshOnDetailReload, null, null,
                ResolvedUiRule.constant(Boolean.TRUE));
    }

    /** Source-compatible canonical constructor before embedded child relations were explicit. */
    public ResolvedDetailRelationDescriptor(String code, String title, boolean readOnly,
                                            String sourceModuleAlias, String sourceEntityAlias,
                                            String targetModuleAlias, String targetEntityAlias,
                                            String parentBinding,
                                            ResolvedDetailRelationQueryContract queryContract,
                                            ResolvedDetailRelationMutationContract mutationContract,
                                            ResolvedDetailRelationParentConstraint parentConstraint,
                                            ResolvedDetailRelationEditing editing,
                                            boolean refreshOnDetailReload) {
        this(code, title, readOnly, sourceModuleAlias, sourceEntityAlias, targetModuleAlias,
                targetEntityAlias, parentBinding, queryContract, mutationContract, parentConstraint,
                editing, refreshOnDetailReload, null,
                queryContract == null ? null : queryContract.listProjection(),
                ResolvedUiRule.constant(Boolean.TRUE));
    }
    public ResolvedDetailRelationDescriptor {
        code = PlatformNameRules.requireIdentifier(code, "detail relation code");
        title = normalize(title);
        sourceModuleAlias = PlatformNameRules.requireModuleAlias(sourceModuleAlias);
        sourceEntityAlias = PlatformNameRules.requireIdentifier(sourceEntityAlias, "source entity alias");
        targetModuleAlias = PlatformNameRules.requireModuleAlias(targetModuleAlias);
        targetEntityAlias = PlatformNameRules.requireIdentifier(targetEntityAlias, "target entity alias");
        parentBinding = requireText(parentBinding, "detail relation parent binding");
        editing = editing == null ? ResolvedDetailRelationEditing.DEFAULT : editing;
        embeddedField = normalize(embeddedField);
        visible = visible == null ? ResolvedUiRule.constant(Boolean.TRUE) : visible;
        if (readOnly && mutationContract != null) {
            throw new IllegalArgumentException("read-only detail relation must not declare mutations");
        }
        if (mutationContract != null && (queryContract == null || !queryContract.managedGateway())) {
            throw new IllegalArgumentException("detail relation mutations require a managed gateway query");
        }
    }

    public boolean hasExecutableQueryContract() {
        return queryContract != null;
    }

    public boolean hasExecutableMutationContract() {
        return !readOnly && mutationContract != null && mutationContract.hasAnyMutation();
    }

    public ResolvedDetailRelationDescriptor withTitle(String value) {
        return new ResolvedDetailRelationDescriptor(code, value, readOnly, sourceModuleAlias, sourceEntityAlias,
                targetModuleAlias, targetEntityAlias, parentBinding, queryContract, mutationContract,
                parentConstraint, editing, refreshOnDetailReload, embeddedField, listProjection, visible);
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
