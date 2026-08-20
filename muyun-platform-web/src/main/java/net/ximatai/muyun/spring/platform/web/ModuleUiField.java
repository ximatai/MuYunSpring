package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/**
 * A declared main-record field used by the static UI DSL.
 *
 * <p>This is deliberately a small value type: static Java models still provide the authoritative
 * field facts during module compilation, while this façade keeps reusable page declarations from
 * passing unlabelled strings through fields, bindings and navigator targets.</p>
 */
public record ModuleUiField(String name) {
    public ModuleUiField {
        name = PlatformNameRules.requireFieldName(name, "module UI field");
    }

    public static ModuleUiField of(String name) {
        return new ModuleUiField(name);
    }
}
