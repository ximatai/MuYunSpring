package net.ximatai.muyun.spring.common.platform;

public enum PlatformActionGroup {
    MENU(EntityCapability.CRUD),
    CRUD(EntityCapability.CRUD),
    SORT(EntityCapability.SORT),
    TREE(EntityCapability.TREE),
    REFERENCE(EntityCapability.REFERENCE),
    ENABLE(EntityCapability.ENABLE),
    RECYCLE_BIN(EntityCapability.RECYCLE_BIN),
    DATA_SCOPE(EntityCapability.DATA_SCOPE),
    EXCHANGE(EntityCapability.EXCHANGE);

    private final EntityCapability capability;

    PlatformActionGroup(EntityCapability capability) {
        this.capability = capability;
    }

    public EntityCapability capability() {
        return capability;
    }
}
