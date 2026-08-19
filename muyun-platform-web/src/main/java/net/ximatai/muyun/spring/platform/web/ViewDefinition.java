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
                             List<FormGroupDefinition> formGroups,
                             List<FormComputeRuleDefinition> formComputeRules) {
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
        formGroups = formGroups == null ? List.of() : List.copyOf(formGroups);
        if (!formGroups.isEmpty() && viewKind != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("form groups are only supported by form views: " + viewCode);
        }
        formComputeRules = formComputeRules == null ? List.of() : List.copyOf(formComputeRules);
        if (!formComputeRules.isEmpty() && viewKind != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("form compute rules are only supported by form views: " + viewCode);
        }
    }

    /** Source-compatible constructor for views declared before form computations were introduced. */
    public ViewDefinition(String viewCode, ModuleViewKind viewKind, ModuleUiClientType clientType, String title,
                          List<ViewFieldDefinition> fields, String sourceUiConfigId,
                          List<FormGroupDefinition> formGroups) {
        this(viewCode, viewKind, clientType, title, fields, sourceUiConfigId, formGroups, List.of());
    }

    public ViewDefinition(String viewCode, ModuleViewKind viewKind, ModuleUiClientType clientType, String title,
                          List<ViewFieldDefinition> fields) {
        this(viewCode, viewKind, clientType, title, fields, null, null, List.of());
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
        private final List<ViewFieldDefinition> fields = new ArrayList<>();
        private final List<FormGroupDefinition> formGroups = new ArrayList<>();
        private final List<FormComputeRuleDefinition> formComputeRules = new ArrayList<>();

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

        /** Declares one deterministic main-record calculation for this form. */
        public Builder formCompute(String code, String targetField, List<String> triggerFields, String expression) {
            if (viewKind != ModuleViewKind.FORM) {
                throw new IllegalStateException("form compute rules are only supported by form views");
            }
            formComputeRules.add(new FormComputeRuleDefinition(code, targetField, triggerFields, expression));
            return this;
        }

        public ViewDefinition build() {
            return new ViewDefinition(viewCode, viewKind, clientType, title, fields, sourceUiConfigId, formGroups,
                    formComputeRules);
        }
    }
}
