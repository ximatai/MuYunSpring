package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;

import java.util.function.Consumer;

/** Dynamic field contract and creation default of ENABLE. */
public final class EnableCapabilityDefinitionFacet {
    public void validate(EntityDefinition entity, FieldDefinition field) {
        if (field == null) {
            throw new ModuleDefinitionException("ENABLE capability requires standard field enabled: " + entity.alias());
        }
        if (!PlatformAbilityFields.ENABLED_FIELD.equals(field.fieldName())
                || !PlatformAbilityFields.ENABLED_COLUMN.equals(field.columnName())
                || field.type() != FieldType.BOOLEAN) {
            throw new ModuleDefinitionException("ENABLE capability requires standard field enabled/enabled: " + entity.alias());
        }
    }

    public void applyCreateDefault(Boolean currentValue, Consumer<Boolean> valueWriter) {
        if (currentValue == null) {
            valueWriter.accept(Boolean.TRUE);
        }
    }
}
