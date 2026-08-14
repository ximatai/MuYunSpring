package net.ximatai.muyun.spring.ability.security;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.common.security.FieldProtectionException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface FieldProtectionAbility<T extends EntityContract> extends CrudAbility<T> {
    default FieldProtectionPlan<T> fieldProtectionPlan() {
        @SuppressWarnings("unchecked")
        FieldProtectionPlan<T> resolved = StaticFieldProtectionResolver.resolve(modelClass());
        return resolved;
    }

    default FieldCryptoProvider fieldCryptoProvider() {
        return FieldCryptoProvider.UNAVAILABLE;
    }

    default FieldSigner fieldSigner() {
        return FieldSigner.UNAVAILABLE;
    }

    default FieldMasker fieldMasker() {
        return FieldMasker.DEFAULT;
    }

    @Override
    default PageResult<T> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        PageResult<T> page = getDao().pageQuery(activeCriteria(criteria), pageRequest, sorts);
        List<T> records = page.getRecords().stream()
                .peek(this::restoreProtectedFieldsFromStorage)
                .toList();
        populateDeclaredReferenceLoads(records);
        return PageResult.of(records, page.getTotal(), pageRequest);
    }

    @Override
    default List<T> list(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        List<T> records = getDao().query(activeCriteria(criteria), pageRequest, sorts).stream()
                .peek(this::restoreProtectedFieldsFromStorage)
                .toList();
        populateDeclaredReferenceLoads(records);
        return records;
    }

    @Override
    default List<T> list(Criteria criteria, Sort... sorts) {
        List<T> records = getDao().list(activeCriteria(criteria), sorts).stream()
                .peek(this::restoreProtectedFieldsFromStorage)
                .toList();
        populateDeclaredReferenceLoads(records);
        return records;
    }

    default FieldProtectionMutation protectFieldsForStorage(T entity) {
        FieldProtectionPlan<T> plan = fieldProtectionPlan();
        if (entity == null || plan.isEmpty()) {
            return FieldProtectionMutation.NONE;
        }
        Map<ProtectedFieldAccessor<T>, Object> originalValues = new LinkedHashMap<>();
        Map<ProtectedFieldAccessor<T>, Object> originalSignatures = new LinkedHashMap<>();
        for (ProtectedFieldAccessor<T> field : plan.fields()) {
            if (!field.shouldWrite(entity) || !field.protection().hasStorageProtection()) {
                continue;
            }
            Object plainValue = field.get(entity);
            originalValues.put(field, plainValue);
            if (field.protection().signatureMode().enabled()) {
                originalSignatures.put(field, field.getSignature(entity));
                field.setSignature(entity, plainValue == null ? null : fieldSigner().sign(field.fieldName(), plainValue));
            }
            if (field.protection().encryptionMode() == FieldEncryptionMode.ENCRYPTED) {
                field.set(entity, plainValue == null ? null : fieldCryptoProvider().encrypt(field.fieldName(), plainValue));
            }
        }
        if (originalValues.isEmpty() && originalSignatures.isEmpty()) {
            return FieldProtectionMutation.NONE;
        }
        return () -> {
            originalValues.forEach((field, value) -> field.set(entity, value));
            originalSignatures.forEach((field, value) -> field.setSignature(entity, value == null ? null : String.valueOf(value)));
        };
    }

    default void restoreProtectedFieldsFromStorage(T entity) {
        FieldProtectionPlan<T> plan = fieldProtectionPlan();
        if (entity == null || plan.isEmpty()) {
            return;
        }
        for (ProtectedFieldAccessor<T> field : plan.fields()) {
            if (!field.hasValue(entity) || !field.protection().hasStorageProtection()) {
                continue;
            }
            Object value = field.get(entity);
            Object plainValue = value;
            if (field.protection().encryptionMode() == FieldEncryptionMode.ENCRYPTED) {
                plainValue = fieldCryptoProvider().decrypt(field.fieldName(), String.valueOf(value));
                field.set(entity, plainValue);
            }
            if (field.protection().signatureMode().enabled()) {
                Object signature = field.getSignature(entity);
                fieldSigner().verify(field.fieldName(), plainValue, signature == null ? null : String.valueOf(signature));
            }
        }
    }

    default Object maskProtectedValue(String fieldName, Object value, FieldOutputContext context) {
        if (fieldName == null || fieldName.isBlank()) {
            return value;
        }
        for (ProtectedFieldAccessor<T> field : fieldProtectionPlan().fields()) {
            if (field.fieldName().equals(fieldName) && field.protection().hasOutputProtection()) {
                return FieldOutputRenderer.renderValue(fieldName, value, field.protection(), context, fieldMasker());
            }
        }
        return value;
    }

    default T maskProtectedFieldsForOutput(T entity, FieldOutputContext context) {
        return FieldOutputRenderer.renderRecord(entity, fieldProtectionPlan(), context, fieldMasker());
    }

    @FunctionalInterface
    interface FieldProtectionMutation extends AutoCloseable {
        FieldProtectionMutation NONE = () -> {
        };

        @Override
        void close() throws FieldProtectionException;
    }
}
