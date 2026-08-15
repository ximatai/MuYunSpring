package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A selected navigator level value supplied to a downstream navigator source. */
public record PlatformPageNavigatorChildBinding(String childLevelKey, String childQueryCriteriaKey) {
    public PlatformPageNavigatorChildBinding {
        childLevelKey = PlatformNameRules.requireFieldName(childLevelKey, "navigator child level key");
        childQueryCriteriaKey = PlatformNameRules.requireFieldName(childQueryCriteriaKey,
                "navigator child query criteria key");
    }
}
