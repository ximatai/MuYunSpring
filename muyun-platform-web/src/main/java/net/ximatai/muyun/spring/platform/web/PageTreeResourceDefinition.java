package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/**
 * Declares the executable main tree of a {@link ModulePageTemplate#TREE_MANAGEMENT} page.
 *
 * <p>The containing module remains the page and authorization owner.  {@code resource} names a
 * registered static action contribution; {@code scopeNavigatorKey} supplies the persisted parent
 * selected in the page navigator.  The browser never receives a business-specific route.</p>
 */
public record PageTreeResourceDefinition(String resource, String scopeNavigatorKey, String scopeField,
                                         String title, String emptyDescription, String createTitle) {
    public PageTreeResourceDefinition {
        resource = PlatformNameRules.requireFieldName(resource, "tree resource");
        scopeNavigatorKey = PlatformNameRules.requireFieldName(scopeNavigatorKey, "tree resource scope navigator");
        scopeField = PlatformNameRules.requireFieldName(scopeField, "tree resource scope field");
        title = title == null || title.isBlank() ? resource : title.trim();
        emptyDescription = emptyDescription == null || emptyDescription.isBlank()
                ? "当前范围暂无" + title : emptyDescription.trim();
        createTitle = createTitle == null || createTitle.isBlank() ? "新建" + title : createTitle.trim();
    }

    public static final class Builder {
        private final String resource;
        private final String scopeNavigatorKey;
        private final String scopeField;
        private String title;
        private String emptyDescription;
        private String createTitle;

        Builder(String resource, String scopeNavigatorKey, String scopeField) {
            this.resource = resource;
            this.scopeNavigatorKey = scopeNavigatorKey;
            this.scopeField = scopeField;
        }

        public Builder title(String value) { title = value; return this; }
        public Builder emptyDescription(String value) { emptyDescription = value; return this; }
        public Builder createTitle(String value) { createTitle = value; return this; }

        PageTreeResourceDefinition build() {
            return new PageTreeResourceDefinition(resource, scopeNavigatorKey, scopeField, title, emptyDescription, createTitle);
        }
    }
}
