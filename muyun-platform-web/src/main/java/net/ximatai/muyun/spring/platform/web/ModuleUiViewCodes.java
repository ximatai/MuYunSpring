package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

public final class ModuleUiViewCodes {
    public static final String DEFAULT_LIST = "default_list";
    public static final String DEFAULT_FORM = "default_form";

    private ModuleUiViewCodes() {
    }

    public static String childResourceDefaultForm(String resource) {
        String validResource = PlatformNameRules.requireIdentifier(resource, "resource");
        return validResource + "_default_form";
    }
}
