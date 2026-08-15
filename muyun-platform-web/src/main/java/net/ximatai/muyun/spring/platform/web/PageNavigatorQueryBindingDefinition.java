package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A selected navigator value applied directly to the owning page list. */
public record PageNavigatorQueryBindingDefinition(String field, String queryCriteriaKey) {
    public PageNavigatorQueryBindingDefinition {
        field = PlatformNameRules.requireFieldName(field, "navigator query field");
        queryCriteriaKey = PlatformNameRules.requireFieldName(
                queryCriteriaKey == null || queryCriteriaKey.isBlank() ? field : queryCriteriaKey,
                "navigator query criteria key");
    }
}
