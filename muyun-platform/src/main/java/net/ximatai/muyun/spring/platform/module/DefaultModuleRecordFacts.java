package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DefaultModuleRecordFacts implements ModuleRecordFacts {
    private final ObjectProvider<CrudAbility<?>> abilities;
    private final DynamicRecordService records;

    public DefaultModuleRecordFacts(ObjectProvider<CrudAbility<?>> abilities, DynamicRecordService records) {
        this.abilities = abilities;
        this.records = records;
    }

    @Override
    public String displayTitle(String moduleAlias, String recordId) {
        var ability = abilities.orderedStream().filter(item -> moduleAlias.equals(item.getModuleAlias())).findFirst();
        if (ability.isEmpty()) {
            var record = records.select(moduleAlias, records.mainEntityAlias(moduleAlias), recordId);
            if (record == null) return null;
            Object value = record.outputValues(net.ximatai.muyun.spring.common.security.FieldOutputContext.LIST).get("title");
            return value == null ? null : String.valueOf(value);
        }
        var record = ability.get().select(recordId);
        if (record == null) return null;
        String field = net.ximatai.muyun.spring.common.model.title.TitleFieldResolver
                .resolveFieldName(record.getClass()).orElse("title");
        String value = net.ximatai.muyun.spring.common.model.title.TitleFieldResolver.readAsString(record);
        if (value == null && record instanceof net.ximatai.muyun.spring.common.model.capability.TitledCapable titled)
            value = titled.getTitle();
        Object rendered = ability.get() instanceof net.ximatai.muyun.spring.ability.security.FieldProtectionAbility<?> protectedAbility
                ? protectedAbility.maskProtectedValue(field, value, net.ximatai.muyun.spring.common.security.FieldOutputContext.LIST)
                : value;
        return rendered == null ? null : String.valueOf(rendered);
    }

    @Override
    public Map<String, Object> read(String moduleAlias, String recordId) {
        var ability = abilities.orderedStream().filter(item -> moduleAlias.equals(item.getModuleAlias()))
                .findFirst();
        if (ability.isEmpty()) {
            var record = records.select(moduleAlias, records.mainEntityAlias(moduleAlias), recordId);
            if (record == null) throw new PlatformException("workflow business record not found: " + recordId);
            Map<String, Object> values = new LinkedHashMap<>(record.getValues());
            values.put("id", record.getId());
            values.put("tenantId", record.getTenantId());
            values.put("version", record.getVersion());
            values.put("deleted", record.getDeleted());
            values.put("deletedAt", record.getDeletedAt());
            values.put("deletedBy", record.getDeletedBy());
            values.putAll(net.ximatai.muyun.spring.ability.reference.PlatformAuditReferences.values(record));
            return values;
        }
        var record = ability.get().select(recordId);
        if (record == null) throw new PlatformException("workflow business record not found: " + recordId);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Class<?> type = record.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    values.putIfAbsent(field.getName(), field.get(record));
                } catch (IllegalAccessException failure) {
                    throw new PlatformException("cannot read workflow business fact: " + field.getName(), failure);
                }
            }
        }
        return values;
    }
}
