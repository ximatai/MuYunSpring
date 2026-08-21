package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A browser-executable visibility fact whose authoritative check remains on the server. */
public record ResolvedDetailRelationParentConstraint(String fieldName, String expectedValue) {
    public ResolvedDetailRelationParentConstraint {
        fieldName = PlatformNameRules.requireFieldName(fieldName, "detail relation parent constraint field");
        if (expectedValue == null || expectedValue.isBlank()) {
            throw new IllegalArgumentException("detail relation parent constraint value must not be blank");
        }
        expectedValue = expectedValue.trim();
    }
}
