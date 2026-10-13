package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.i18n.TextReference;

/** Source identities belong to the configuration producer, shared by every display adapter. */
public final class ConfigurationTextSources {
    private ConfigurationTextSources() { }
    public static TextReference menuTitle(String menuId, String original) {
        return TextReference.configured("config/menu/" + segment(menuId) + "/title", original);
    }
    public static TextReference dictionaryTitle(String itemId, String original) {
        return TextReference.configured("config/dictionary/item/" + segment(itemId) + "/title", original);
    }
    public static TextReference workflowNodeTitle(String versionId, String nodeKey, String original) {
        return TextReference.configured("config/workflow/version/" + segment(versionId) + "/node/" + segment(nodeKey) + "/title", original);
    }
    public static TextReference metadataFieldTitle(String module, String entity, String field, String original) {
        return TextReference.configured("config/metadata/" + segment(module) + "/entity/" + segment(entity) + "/field/" + segment(field) + "/title", original);
    }
    private static String segment(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Configuration source identity is required");
        return value.replace("~", "~0").replace("/", "~1");
    }
}
