package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class ReferenceFieldResolver {
    private ReferenceFieldResolver() {
    }

    static Object read(Object record, String fieldName) {
        if (record == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        Class<?> current = record.getClass();
        while (current != null && !Object.class.equals(current)) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(record);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new PlatformException("Cannot read reference projection field: "
                        + record.getClass().getName() + "." + fieldName, e);
            }
        }
        throw new PlatformException("Cannot find reference projection field: "
                + record.getClass().getName() + "." + fieldName);
    }

    static void requireReadable(Class<?> type, String fieldName) {
        if (type == null || fieldName == null || fieldName.isBlank()) {
            throw new PlatformException("reference field must not be blank");
        }
        Class<?> current = type;
        while (current != null && !Object.class.equals(current)) {
            try {
                current.getDeclaredField(fieldName);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new PlatformException("Cannot find reference projection field: " + type.getName() + "." + fieldName);
    }

    static boolean isReadable(Class<?> type, String fieldName) {
        try {
            requireReadable(type, fieldName);
            return true;
        } catch (PlatformException ignored) {
            return false;
        }
    }

    static List<String> readableFieldNames(Class<?> type) {
        if (type == null) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Class<?> current = type;
        while (current != null && !Object.class.equals(current)) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    names.add(field.getName());
                }
            }
            current = current.getSuperclass();
        }
        List<String> readable = new ArrayList<>(names);
        readable.sort(String::compareTo);
        return List.copyOf(readable);
    }
}
