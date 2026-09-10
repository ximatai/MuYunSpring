package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

public record BusinessRuleGovernanceSnapshot(String moduleAlias, String baselineFingerprint,
                                             List<BusinessRuleField> editableFields,
                                             List<BusinessRuleSnapshotRule> rules,
                                             List<BusinessRuleReferenceField> referenceFields,
                                             List<BusinessRuleFunction> functions) {
    public BusinessRuleGovernanceSnapshot {
        editableFields = editableFields == null ? List.of() : List.copyOf(editableFields);
        rules = rules == null ? List.of() : List.copyOf(rules);
        referenceFields = referenceFields == null ? List.of() : List.copyOf(referenceFields);
        functions = functions == null ? List.of() : List.copyOf(functions);
    }

    public BusinessRuleGovernanceSnapshot(String moduleAlias, String baselineFingerprint,
                                          List<BusinessRuleField> editableFields,
                                          List<BusinessRuleSnapshotRule> rules) {
        this(moduleAlias, baselineFingerprint, editableFields, rules, List.of(), List.of());
    }
}
