package net.ximatai.muyun.spring.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public record ViewDefinition(String viewCode,
                             ModuleViewKind viewKind,
                             ModuleUiClientType clientType,
                             String title,
                             List<ViewFieldDefinition> fields,
                             String sourceUiConfigId,
                             ScopedListWorkspaceDefinition scopedListWorkspace,
                             List<FormGroupDefinition> formGroups) {
    public ViewDefinition {
        if (viewCode == null || viewCode.isBlank()) {
            throw new IllegalArgumentException("view code must not be blank");
        }
        viewCode = viewCode.trim();
        if (viewKind == null) {
            throw new IllegalArgumentException("view kind must not be null");
        }
        clientType = clientType == null ? ModuleUiClientType.WEB : clientType;
        title = title == null || title.isBlank() ? null : title.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
        sourceUiConfigId = sourceUiConfigId == null || sourceUiConfigId.isBlank() ? null : sourceUiConfigId.trim();
        if (scopedListWorkspace != null && viewKind != ModuleViewKind.LIST) {
            throw new IllegalArgumentException("scoped list workspace is only supported by list views: " + viewCode);
        }
        formGroups = formGroups == null ? List.of() : List.copyOf(formGroups);
        if (!formGroups.isEmpty() && viewKind != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("form groups are only supported by form views: " + viewCode);
        }
    }

    public ViewDefinition(String viewCode, ModuleViewKind viewKind, ModuleUiClientType clientType, String title,
                          List<ViewFieldDefinition> fields) {
        this(viewCode, viewKind, clientType, title, fields, null, null, null);
    }

    public ViewDefinition(String viewCode, ModuleViewKind viewKind, ModuleUiClientType clientType, String title,
                          List<ViewFieldDefinition> fields, String sourceUiConfigId,
                          ScopedListWorkspaceDefinition scopedListWorkspace) {
        this(viewCode, viewKind, clientType, title, fields, sourceUiConfigId, scopedListWorkspace, null);
    }

    public static Builder list() {
        return new Builder("default_list", ModuleViewKind.LIST);
    }

    public static Builder list(String viewCode) {
        return new Builder(viewCode, ModuleViewKind.LIST);
    }

    public static Builder form() {
        return new Builder("default_form", ModuleViewKind.FORM);
    }

    public static Builder form(String viewCode) {
        return new Builder(viewCode, ModuleViewKind.FORM);
    }

    public static final class Builder {
        private final String viewCode;
        private final ModuleViewKind viewKind;
        private ModuleUiClientType clientType = ModuleUiClientType.WEB;
        private String title;
        private String sourceUiConfigId;
        private ScopedListWorkspaceDefinition scopedListWorkspace;
        private final List<ViewFieldDefinition> fields = new ArrayList<>();
        private final List<FormGroupDefinition> formGroups = new ArrayList<>();

        private Builder(String viewCode, ModuleViewKind viewKind) {
            this.viewCode = viewCode;
            this.viewKind = viewKind;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        Builder sourceUiConfigId(String sourceUiConfigId) {
            this.sourceUiConfigId = sourceUiConfigId;
            return this;
        }

        /** Uses {@code scopeField} as the standard external-query key. */
        public Builder scopedListWorkspace(String scopeModuleAlias, String scopeField,
                                           String scopeTitle, String scopeSearchPlaceholder) {
            return scopedListWorkspace(scopeModuleAlias, scopeField, scopeField, scopeTitle, scopeSearchPlaceholder,
                    false, ScopedListWorkspaceCreatePolicy.ALLOW_UNSCOPED);
        }

        public Builder scopedListWorkspace(String scopeModuleAlias, String scopeField, String queryCriteriaKey,
                                           String scopeTitle, String scopeSearchPlaceholder,
                                           boolean showScopeItemSubtitle,
                                           ScopedListWorkspaceCreatePolicy createPolicy) {
            scopedListWorkspace = new ScopedListWorkspaceDefinition(scopeModuleAlias, scopeField, queryCriteriaKey,
                    scopeTitle, scopeSearchPlaceholder, showScopeItemSubtitle, createPolicy);
            return this;
        }

        /** Enables standard CRUD management for a tree-shaped scope in this workspace. */
        public Builder manageableScopedTree() {
            if (scopedListWorkspace == null) {
                throw new IllegalStateException("scopedListWorkspace must be configured before manageableScopedTree");
            }
            scopedListWorkspace = new ScopedListWorkspaceDefinition(scopedListWorkspace.scopeModuleAlias(),
                    scopedListWorkspace.scopeField(), scopedListWorkspace.queryCriteriaKey(),
                    scopedListWorkspace.scopeTitle(), scopedListWorkspace.scopeSearchPlaceholder(),
                    scopedListWorkspace.showScopeItemSubtitle(), scopedListWorkspace.createPolicy(), true);
            return this;
        }

        public Builder field(String fieldName) {
            return field(fieldName, ignored -> {
            });
        }

        public Builder field(String fieldName, Consumer<ViewFieldDefinition.Builder> customizer) {
            ViewFieldDefinition.Builder builder = ViewFieldDefinition.field(fieldName);
            if (customizer != null) {
                customizer.accept(builder);
            }
            fields.add(builder.build());
            return this;
        }

        public Builder field(String relationCode, String fieldName, Consumer<ViewFieldDefinition.Builder> customizer) {
            ViewFieldDefinition.Builder builder = ViewFieldDefinition.field(relationCode, fieldName);
            if (customizer != null) {
                customizer.accept(builder);
            }
            fields.add(builder.build());
            return this;
        }

        /** Adds a semantic form group that owns its nested fields. */
        public Builder group(String groupCode, String title, String subtitle,
                             Consumer<FormGroupDefinition.Builder> customizer) {
            if (viewKind != ModuleViewKind.FORM) {
                throw new IllegalStateException("form groups are only supported by form views");
            }
            FormGroupDefinition.Builder builder = FormGroupDefinition.builder(groupCode, title, subtitle);
            if (customizer != null) {
                customizer.accept(builder);
            }
            FormGroupDefinition group = builder.build();
            formGroups.add(group);
            fields.addAll(group.fields());
            return this;
        }

        public ViewDefinition build() {
            return new ViewDefinition(viewCode, viewKind, clientType, title, fields, sourceUiConfigId,
                    scopedListWorkspace, formGroups);
        }
    }
}
