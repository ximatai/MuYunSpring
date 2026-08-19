package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A server-owned page-context source key, for example a current-session value. */
public record ModuleUiBindingKey(String value) {
    public ModuleUiBindingKey {
        value = PlatformNameRules.requireFieldName(value, "module UI binding key");
    }

    public static ModuleUiBindingKey of(String value) {
        return new ModuleUiBindingKey(value);
    }
}
