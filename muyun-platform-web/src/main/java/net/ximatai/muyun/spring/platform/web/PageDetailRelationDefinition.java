package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.ability.child.AggregateChildFormulaDefinition;

import java.util.List;

/** Static declaration of a detail relation. It deliberately does not imply a query endpoint. */
public record PageDetailRelationDefinition(String code, String title, String targetEntityAlias,
                                           String parentBinding, boolean readOnly,
                                           boolean managedQuery,
                                           PageDetailRelationMutationDefinition mutation,
                                           PageDetailRelationParentConstraintDefinition parentConstraint,
                                           PageDetailRelationPaginationDefinition pagination,
                                           PageDetailRelationEditingDefinition editing,
                                           boolean refreshOnDetailReload,
                                           boolean embedded,
                                           List<AggregateChildFormulaDefinition> formComputeRules,
                                           UiRule<Boolean> visible,
                                           List<String> listFields) {
    /** Source-compatible declaration for a read-only relation. */
    public PageDetailRelationDefinition(String code, String title, String targetEntityAlias,
                                        String parentBinding, boolean readOnly,
                                        boolean refreshOnDetailReload) {
        this(code, title, targetEntityAlias, parentBinding, readOnly, false, null, null,
                PageDetailRelationPaginationDefinition.DEFAULT, PageDetailRelationEditingDefinition.DEFAULT,
                refreshOnDetailReload, false, List.of(), UiRule.constant(Boolean.TRUE), List.of());
    }

    /** Read-only relation with an explicit list projection chosen by the page composition. */
    public PageDetailRelationDefinition(String code, String title, String targetEntityAlias,
                                        String parentBinding, boolean readOnly,
                                        boolean refreshOnDetailReload, List<String> listFields) {
        this(code, title, targetEntityAlias, parentBinding, readOnly, false, null, null,
                PageDetailRelationPaginationDefinition.DEFAULT, PageDetailRelationEditingDefinition.DEFAULT,
                refreshOnDetailReload, false, List.of(), UiRule.constant(Boolean.TRUE), listFields);
    }

    public PageDetailRelationDefinition(String code, String title, String targetEntityAlias,
                                        String parentBinding, boolean readOnly,
                                        PageDetailRelationMutationDefinition mutation,
                                        boolean refreshOnDetailReload) {
        this(code, title, targetEntityAlias, parentBinding, readOnly, mutation != null, mutation, null,
                PageDetailRelationPaginationDefinition.DEFAULT, PageDetailRelationEditingDefinition.DEFAULT,
                refreshOnDetailReload, false, List.of(), UiRule.constant(Boolean.TRUE), List.of());
    }

    public PageDetailRelationDefinition {
        code = PlatformNameRules.requireIdentifier(code, "detail relation code");
        title = title == null || title.isBlank() ? null : title.trim();
        targetEntityAlias = PlatformNameRules.requireIdentifier(targetEntityAlias, "detail relation target entity alias");
        parentBinding = requireText(parentBinding, "detail relation parent binding");
        if (readOnly && mutation != null) {
            throw new IllegalArgumentException("read-only detail relation must not declare mutations");
        }
        if (mutation != null && !managedQuery) {
            throw new IllegalArgumentException("detail relation mutations require an explicit managed query");
        }
        pagination = pagination == null ? PageDetailRelationPaginationDefinition.DEFAULT : pagination;
        editing = editing == null ? PageDetailRelationEditingDefinition.DEFAULT : editing;
        formComputeRules = formComputeRules == null ? List.of() : List.copyOf(formComputeRules);
        visible = visible == null ? UiRule.constant(Boolean.TRUE) : visible;
        listFields = listFields == null ? List.of() : List.copyOf(listFields);
        if (listFields.stream().anyMatch(field -> field == null || field.isBlank())
                || listFields.stream().map(String::trim).distinct().count() != listFields.size()) {
            throw new IllegalArgumentException("detail relation list fields must be non-blank and unique");
        }
        if (embedded && (managedQuery || mutation != null || readOnly)) {
            throw new IllegalArgumentException("embedded child relation is edited through its parent CRUD contract");
        }
        if (editing.saveMode() == PageDetailRelationEditingDefinition.SaveMode.AGGREGATE_DRAFT && !embedded) {
            throw new IllegalArgumentException("aggregate relation drafts require an embedded child relation");
        }
        if (!embedded && !formComputeRules.isEmpty()) {
            throw new IllegalArgumentException("relation form compute rules require an embedded child relation");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
