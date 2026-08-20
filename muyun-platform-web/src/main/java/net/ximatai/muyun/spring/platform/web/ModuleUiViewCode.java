package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A validated, reusable view code for a static module UI declaration. */
public record ModuleUiViewCode(String value) {
    public ModuleUiViewCode {
        value = PlatformNameRules.requireIdentifier(value, "module UI view code");
    }

    public static ModuleUiViewCode of(String value) {
        return new ModuleUiViewCode(value);
    }
}
