package net.ximatai.muyun.spring.platform.module;

import java.util.List;

/** The delivery-independent part of a static module definition used for platform registration. */
public interface StaticModuleRegistration {
    String applicationAlias();

    String moduleAlias();

    String title();

    default String description() { return null; }

    String parentModuleAlias();

    ModuleEntryType entryType();

    String entryRoute();

    String entryExternalUrl();

    List<StaticModuleActionDefinition> actions();
}
