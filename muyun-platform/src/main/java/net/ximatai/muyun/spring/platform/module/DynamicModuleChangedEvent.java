package net.ximatai.muyun.spring.platform.module;

/** Published within the mutation transaction; tenantId identifies the definition owner. */
public record DynamicModuleChangedEvent(String moduleAlias, String tenantId) {
    public DynamicModuleChangedEvent(String moduleAlias) {
        this(moduleAlias, null);
    }
}
