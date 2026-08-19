package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;

/** Dynamic parent-field contract owned by TREE. */
public final class TreeCapabilityDefinitionFacet {
    public void validate(EntityDefinition entity) {
        FieldDefinition field = entity.fields().stream()
                .filter(candidate -> PlatformAbilityFields.TREE_PARENT_FIELD.equals(candidate.fieldName()))
                .findFirst()
                .orElse(null);
        if (field == null) {
            throw new ModuleDefinitionException("TREE capability requires standard field parentId: " + entity.alias());
        }
        if (!PlatformAbilityFields.TREE_PARENT_COLUMN.equals(field.columnName())
                || field.type() != FieldType.STRING
                || !Integer.valueOf(PlatformAbilityFields.TREE_PARENT_LENGTH).equals(field.length())) {
            throw new ModuleDefinitionException("TREE capability requires standard field parentId/parent_id: "
                    + entity.alias());
        }
    }
}
