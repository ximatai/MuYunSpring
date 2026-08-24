package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;

/** Source-neutral UI descriptor for one persistent boolean list query control. */
public record ResolvedPageListPersistentQueryControlDescriptor(String externalCriteriaKey, String title,
                                                               ViewControlType uiType, boolean defaultValue) {
    public static ResolvedPageListPersistentQueryControlDescriptor from(
            PageListPersistentQueryControlDefinition definition) {
        return new ResolvedPageListPersistentQueryControlDescriptor(definition.externalCriteriaKey(), definition.title(),
                definition.uiType(), (Boolean) definition.defaultValue());
    }
}
