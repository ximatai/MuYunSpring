package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import java.util.List;

/** Read descriptors of existing standard columns; never added to entity schema definitions. */
public final class DynamicAuditFields {
    private DynamicAuditFields() { }

    public static List<FieldDefinition> definitions() {
        return PlatformFieldPolicy.auditFields().stream().map(DynamicAuditFields::definition).toList();
    }
    private static FieldDefinition definition(PlatformFieldPolicy policy) {
        FieldType type = policy.referenceModuleAlias() == null ? FieldType.TIMESTAMP : FieldType.STRING;
        return new FieldDefinition(policy.fieldName(), StandardEntitySchema.columnName(policy.fieldName()), type,
                policy.title()).writeProtected().notCopyable().sortable().queryable();
    }

    public static List<DynamicFieldDescriptor> descriptors(String entityAlias) {
        return PlatformFieldPolicy.auditFields().stream().map(policy -> {
            FieldDefinition field = definition(policy);
            DynamicReferenceDescriptor reference = policy.referenceModuleAlias() == null ? null :
                    new DynamicReferenceDescriptor(entityAlias, policy.fieldName(), policy.referenceModuleAlias(),
                            ReferenceTarget.parse(policy.referenceModuleAlias()).entityAlias(),
                            ReferenceCardinality.ONE, List.of(new DynamicReferenceProjectionDescriptor("title", policy.fieldName() + "Title")),
                            "id", "title", null, null, java.util.Set.of(), List.of(), List.of(),
                            ReferenceIntegrityPolicy.DEFAULT, ReferenceTenantScope.GLOBAL);
            return DynamicFieldDescriptor.from(field, reference);
        }).toList();
    }
}
