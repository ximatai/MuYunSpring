package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Static DSL fact used to hide an inapplicable relation before any request is issued. */
public record PageDetailRelationParentConstraintDefinition(String fieldName, String expectedValue) {
    public PageDetailRelationParentConstraintDefinition {
        fieldName = PlatformNameRules.requireFieldName(fieldName, "detail relation parent constraint field");
        if (expectedValue == null || expectedValue.isBlank()) {
            throw new IllegalArgumentException("detail relation parent constraint value must not be blank");
        }
        expectedValue = expectedValue.trim();
    }

    public static PageDetailRelationParentConstraintDefinition fieldEquals(String fieldName, String expectedValue) {
        return new PageDetailRelationParentConstraintDefinition(fieldName, expectedValue);
    }
}
