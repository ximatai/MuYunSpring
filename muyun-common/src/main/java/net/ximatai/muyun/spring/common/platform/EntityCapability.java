package net.ximatai.muyun.spring.common.platform;

public enum EntityCapability {
    CRUD(CapabilityKind.BASELINE),
    SOFT_DELETE(CapabilityKind.BASELINE),
    LIFECYCLE(CapabilityKind.BASELINE),
    CACHE(CapabilityKind.BASELINE),
    TREE(CapabilityKind.FIELD_DECLARATION),
    SORT(CapabilityKind.FIELD_DECLARATION),
    REFERENCE(CapabilityKind.FIELD_DECLARATION),
    BUSINESS_CONTENT(CapabilityKind.FIELD_DECLARATION),
    BUSINESS_CODE(CapabilityKind.FIELD_DECLARATION),
    ENABLE(CapabilityKind.FIELD_DECLARATION),
    DATA_SCOPE(CapabilityKind.FIELD_DECLARATION),
    RECYCLE_BIN(CapabilityKind.DEFINITION_DECLARATION),
    WORKFLOW(CapabilityKind.DEFINITION_DECLARATION),
    APPROVAL(CapabilityKind.FIELD_DECLARATION),
    CHILD_RELATION(CapabilityKind.DEFINITION_DECLARATION),
    REFERENCE_DEPENDENCY(CapabilityKind.DEFINITION_DECLARATION),
    EXCHANGE(CapabilityKind.DEFINITION_DECLARATION);

    private final CapabilityKind kind;

    EntityCapability(CapabilityKind kind) {
        this.kind = kind;
    }

    public CapabilityKind kind() {
        return kind;
    }

    public boolean isBaseline() {
        return kind == CapabilityKind.BASELINE;
    }

    public boolean isDeclaredByEntityFields() {
        return kind == CapabilityKind.FIELD_DECLARATION;
    }

    public boolean isDeclaredByDefinition() {
        return kind == CapabilityKind.DEFINITION_DECLARATION;
    }

    public enum CapabilityKind {
        BASELINE,
        FIELD_DECLARATION,
        DEFINITION_DECLARATION
    }
}
