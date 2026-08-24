package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.Preconditions;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;

/**
 * A user-controlled query fact rendered persistently by the standard list toolbar.
 *
 * <p>This is page UI state only. Its key must be accepted by the module query descriptor;
 * the descriptor remains the owner of query semantics and authorization.</p>
 */
public record PageListPersistentQueryControlDefinition(String externalCriteriaKey,
                                                        String title,
                                                        ViewControlType uiType,
                                                        Object defaultValue) {
    public PageListPersistentQueryControlDefinition {
        externalCriteriaKey = Preconditions.requireText(externalCriteriaKey, "external criteria key");
        title = Preconditions.requireText(title, "persistent query control title");
        if (uiType != ViewControlType.SWITCH) {
            throw new IllegalArgumentException("unsupported persistent query control type: " + uiType);
        }
        if (!(defaultValue instanceof Boolean)) {
            throw new IllegalArgumentException("SWITCH persistent query control requires a boolean default value");
        }
    }

    public static Builder builder(String externalCriteriaKey) {
        return new Builder(externalCriteriaKey);
    }

    public static final class Builder {
        private final String externalCriteriaKey;
        private String title;
        private ViewControlType uiType;
        private Object defaultValue;

        private Builder(String externalCriteriaKey) {
            this.externalCriteriaKey = externalCriteriaKey;
        }

        public Builder label(String value) {
            title = value;
            return this;
        }

        public Builder uiType(ViewControlType value) {
            uiType = value;
            return this;
        }

        public Builder defaultValue(Object value) {
            defaultValue = value;
            return this;
        }

        PageListPersistentQueryControlDefinition build() {
            return new PageListPersistentQueryControlDefinition(externalCriteriaKey, title, uiType, defaultValue);
        }
    }
}
