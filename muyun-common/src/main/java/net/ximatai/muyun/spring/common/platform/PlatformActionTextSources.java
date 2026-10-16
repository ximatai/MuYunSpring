package net.ximatai.muyun.spring.common.platform;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.Arrays;

/** Producer-owned identities shared by static declarations and generated/configured dynamic actions. */
public final class PlatformActionTextSources {
    private PlatformActionTextSources() { }

    public static TextReference declared(String moduleAlias, String actionCode, String original) {
        if (original == null) return null;
        String key = Arrays.stream(PlatformAction.values())
                .filter(action -> action.code().equals(actionCode) && action.title().equals(original))
                .map(PlatformAction::titleKey).findFirst().orElse("action/" + segment(moduleAlias) + "/" + segment(actionCode) + "/title");
        return TextReference.code(key, original);
    }

    public static TextReference configured(String moduleAlias, String actionCode, String original) {
        if (original == null) return null;
        return TextReference.configured("config/action/" + segment(moduleAlias) + "/" + segment(actionCode) + "/title", original);
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Action source identity is required");
        return value.replace("~", "~0").replace("/", "~1");
    }
}
