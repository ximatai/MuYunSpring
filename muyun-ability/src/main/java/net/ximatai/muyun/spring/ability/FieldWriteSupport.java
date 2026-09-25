package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.constraint.StaticFieldWriteRules;
import net.ximatai.muyun.spring.common.model.constraint.WriteOperation;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.Objects;

/** Mandatory write-chain execution, independent of overridable business hooks. */
final class FieldWriteSupport {
    private FieldWriteSupport() {}

    @SuppressWarnings("unchecked")
    static <T extends EntityContract> void normalize(CrudAbility<T> service, T record) {
        if (service instanceof FieldWriteRulesProvider<?> adapter) {
            var provider = (FieldWriteRulesProvider<T>) adapter;
            provider.fieldWriteRules().forEach((field, rules) -> {
                if (!provider.hasFieldWriteValue(record, field)) return;
                Object value = provider.fieldWriteValue(record, field);
                Object normalized = rules.normalize(value);
                if (!Objects.equals(value, normalized)) provider.fieldWriteValue(record, field, normalized);
            });
        } else {
            StaticFieldWriteRules.normalize(modelClass(service, record), record);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends EntityContract> void validate(CrudAbility<T> service, T existing, T record, boolean update) {
        if (service instanceof FieldWriteRulesProvider<?> adapter) {
            var provider = (FieldWriteRulesProvider<T>) adapter;
            provider.fieldWriteRules().forEach((field, rules) -> {
                Object value = provider.hasFieldWriteValue(record, field) ? provider.fieldWriteValue(record, field)
                        : update && existing != null ? provider.storedFieldWriteValue(existing, field) : null;
                rules.validate(field, value, update);
            });
        } else {
            StaticFieldWriteRules.validate(modelClass(service, record), record,
                    update ? WriteOperation.UPDATE : WriteOperation.INSERT);
        }
    }

    private static Class<?> modelClass(CrudAbility<?> service, Object record) {
        Class<?> modelClass = service.modelClass();
        return modelClass == null ? record.getClass() : modelClass;
    }
}
