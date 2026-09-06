package net.ximatai.muyun.spring.platform.module;

/** Published within the mutation transaction after a dynamic module definition is persisted. */
public record DynamicModuleChangedEvent(String moduleAlias) {
}
