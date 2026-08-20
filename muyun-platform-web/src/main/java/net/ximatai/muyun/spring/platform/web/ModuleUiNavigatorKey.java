package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A named navigator source in a static module page declaration. */
public record ModuleUiNavigatorKey(String value) {
    public ModuleUiNavigatorKey {
        value = PlatformNameRules.requireFieldName(value, "module UI navigator key");
    }

    public static ModuleUiNavigatorKey of(String value) {
        return new ModuleUiNavigatorKey(value);
    }
}
