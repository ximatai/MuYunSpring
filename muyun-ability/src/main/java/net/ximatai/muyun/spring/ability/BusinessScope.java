package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.Preconditions;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

public final class BusinessScope {
    private BusinessScope() {
    }

    public static Criteria criteria(Object source, String... fieldNames) {
        Objects.requireNonNull(source, "source must not be null");
        Criteria criteria = Criteria.of();
        for (String fieldName : requireFieldNames(fieldNames)) {
            criteria.eqNullable(fieldName, value(source, fieldName));
        }
        return criteria;
    }

    public static void requireSame(Object left, Object right, String message, String... fieldNames) {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(right, "right must not be null");
        for (String fieldName : requireFieldNames(fieldNames)) {
            if (!SortAbility.sameValue(value(left, fieldName), value(right, fieldName))) {
                throw new PlatformException(message);
            }
        }
    }

    private static String[] requireFieldNames(String... fieldNames) {
        Objects.requireNonNull(fieldNames, "fieldNames must not be null");
        if (fieldNames.length == 0) {
            throw new IllegalArgumentException("fieldNames must not be empty");
        }
        for (String fieldName : fieldNames) {
            Preconditions.requireText(fieldName, "fieldName");
        }
        return fieldNames;
    }

    private static Object value(Object source, String fieldName) {
        PropertyDescriptor descriptor = propertyDescriptor(source.getClass(), fieldName);
        if (descriptor != null && descriptor.getReadMethod() != null) {
            try {
                return descriptor.getReadMethod().invoke(source);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new PlatformException("Cannot read scope field: " + fieldName, e);
            }
        }
        Field field = findField(source.getClass(), fieldName);
        if (field == null) {
            throw new PlatformException("Unknown scope field: " + fieldName);
        }
        try {
            field.setAccessible(true);
            return field.get(source);
        } catch (IllegalAccessException e) {
            throw new PlatformException("Cannot read scope field: " + fieldName, e);
        }
    }

    private static PropertyDescriptor propertyDescriptor(Class<?> type, String fieldName) {
        try {
            for (PropertyDescriptor descriptor : Introspector.getBeanInfo(type).getPropertyDescriptors()) {
                if (descriptor.getName().equals(fieldName)) {
                    return descriptor;
                }
            }
            return null;
        } catch (IntrospectionException e) {
            throw new PlatformException("Cannot inspect scope fields for: " + type.getName(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
