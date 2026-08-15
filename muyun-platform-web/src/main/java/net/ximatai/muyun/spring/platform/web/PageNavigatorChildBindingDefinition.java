package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A selected navigator value supplied as an external criterion to a later navigator level. */
public record PageNavigatorChildBindingDefinition(String childLevelKey, String childQueryCriteriaKey) {
    public PageNavigatorChildBindingDefinition {
        childLevelKey = PlatformNameRules.requireFieldName(childLevelKey, "navigator child level key");
        childQueryCriteriaKey = PlatformNameRules.requireFieldName(childQueryCriteriaKey,
                "navigator child query criteria key");
    }
}
