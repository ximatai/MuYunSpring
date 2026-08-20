package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.List;

/** Immutable query-form interpretation compiled with a module execution plan. */
public record ModuleQueryFormField(String fieldName, Mode mode, List<String> bindingKeys) {
    public enum Mode { DEFAULT, BETWEEN }

    public ModuleQueryFormField {
        fieldName = PlatformNameRules.requireFieldName(fieldName, "query form field");
        mode = mode == null ? Mode.DEFAULT : mode;
        bindingKeys = bindingKeys == null ? List.of() : List.copyOf(bindingKeys);
    }
}
