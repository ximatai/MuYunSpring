package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** One descriptor-owned value that constrains the central page list. */
public record PlatformPageNavigatorQueryBinding(String field, String queryCriteriaKey) {
    public PlatformPageNavigatorQueryBinding {
        field = PlatformNameRules.requireFieldName(field, "navigator query field");
        queryCriteriaKey = PlatformNameRules.requireFieldName(
                queryCriteriaKey == null || queryCriteriaKey.isBlank() ? field : queryCriteriaKey,
                "navigator query criteria key");
    }
}
