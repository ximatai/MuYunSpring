package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

public record BusinessRuleGovernanceSnapshot(String moduleAlias, String baselineFingerprint,
                                             List<BusinessRuleField> editableFields,
                                             List<BusinessRuleSnapshotRule> rules,
                                             List<BusinessRuleReferenceField> referenceFields,
                                             List<BusinessRuleField> aggregateFields,
                                             List<BusinessRuleFunction> functions, List<BusinessRuleField> childFields) {
    public BusinessRuleGovernanceSnapshot {
        editableFields = editableFields == null ? List.of() : List.copyOf(editableFields);
        rules = rules == null ? List.of() : List.copyOf(rules);
        referenceFields = referenceFields == null ? List.of() : List.copyOf(referenceFields);
        aggregateFields = aggregateFields == null ? List.of() : List.copyOf(aggregateFields);
        childFields = childFields == null ? List.of() : List.copyOf(childFields);
        functions = functions == null ? List.of() : List.copyOf(functions);
    }

    public BusinessRuleGovernanceSnapshot(String moduleAlias, String baselineFingerprint,
            List<BusinessRuleField> editableFields, List<BusinessRuleSnapshotRule> rules,
            List<BusinessRuleReferenceField> referenceFields, List<BusinessRuleField> aggregateFields,
            List<BusinessRuleFunction> functions) {
        this(moduleAlias, baselineFingerprint, editableFields, rules, referenceFields, aggregateFields, functions, List.of());
    }

    public BusinessRuleGovernanceSnapshot(String moduleAlias, String baselineFingerprint,
                                          List<BusinessRuleField> editableFields,
                                          List<BusinessRuleSnapshotRule> rules) {
        this(moduleAlias, baselineFingerprint, editableFields, rules, List.of(), List.of(), List.of());
    }
}
