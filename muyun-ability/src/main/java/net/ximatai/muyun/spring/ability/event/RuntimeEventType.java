package net.ximatai.muyun.spring.ability.event;

public enum RuntimeEventType {
    AFTER_CREATE(true),
    AFTER_UPDATE(true),
    AFTER_DELETE(true),
    ACTION_EXECUTED(true),
    ACTION_FAILED(true),
    MODULE_REFRESHED(false),
    MODULE_PAGE_CONFIG_PUBLISHED(false);

    private final boolean requiresEntityAlias;

    RuntimeEventType(boolean requiresEntityAlias) {
        this.requiresEntityAlias = requiresEntityAlias;
    }

    public boolean requiresEntityAlias() {
        return requiresEntityAlias;
    }
}
