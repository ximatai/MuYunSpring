package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.child.Children;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

final class EntityRecordCopies {
    private EntityRecordCopies() {
    }

    /** A field command owns its value graph and never submits aggregate child collections. */
    @SuppressWarnings("unchecked")
    static <T> T forFieldMutation(T entity) {
        return (T) detachedValue(entity, new IdentityHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private static Object detachedValue(Object value, IdentityHashMap<Object, Object> copies) {
        if (value == null) return null;
        Class<?> type = value.getClass();
        if (value instanceof Enum<?> || type == String.class || type == Boolean.class || type == Character.class
                || type == Byte.class || type == Short.class || type == Integer.class || type == Long.class
                || type == Float.class || type == Double.class || type == BigDecimal.class
                || type == BigInteger.class || type == UUID.class || type.getPackageName().equals("java.time")) {
            return value;
        }
        if (copies.containsKey(value)) return copies.get(value);
        if (value instanceof Date date) {
            Object copy = date.clone();
            copies.put(value, copy);
            return copy;
        }
        if (type.isArray()) {
            Object copy = Array.newInstance(type.componentType(), Array.getLength(value));
            copies.put(value, copy);
            for (int i = 0; i < Array.getLength(value); i++) Array.set(copy, i, detachedValue(Array.get(value, i), copies));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = map instanceof SortedMap<?, ?> sorted
                    ? new TreeMap<>((Comparator<Object>) sorted.comparator()) : new LinkedHashMap<>();
            copies.put(value, copy);
            map.forEach((key, item) -> copy.put(detachedValue(key, copies), detachedValue(item, copies)));
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            Collection<Object> copy = collection instanceof SortedSet<?> sorted
                    ? new TreeSet<>((Comparator<Object>) sorted.comparator())
                    : collection instanceof Set<?> ? new LinkedHashSet<>()
                    : collection instanceof Queue<?> ? new LinkedList<>() : new ArrayList<>();
            copies.put(value, copy);
            collection.forEach(item -> copy.add(detachedValue(item, copies)));
            return copy;
        }
        if (type.isRecord()) {
            try {
                RecordComponent[] components = type.getRecordComponents();
                Class<?>[] types = new Class<?>[components.length];
                Object[] values = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    types[i] = components[i].getType();
                    var accessor = components[i].getAccessor();
                    accessor.setAccessible(true);
                    values[i] = detachedValue(accessor.invoke(value), copies);
                }
                Constructor<?> constructor = type.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                Object copy = constructor.newInstance(values);
                copies.put(value, copy);
                return copy;
            } catch (ReflectiveOperationException e) {
                throw new PlatformException("cannot copy record value: " + type.getName(), e);
            }
        }
        Object copy = newInstance(value, "field mutation value requires a no-arg constructor");
        copies.put(value, copy);
        for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    // Constructors commonly initialize children to an empty list. Empty means replace,
                    // whereas null means the command did not participate in this relation.
                    field.set(copy, field.isAnnotationPresent(Children.class)
                            ? null : detachedValue(field.get(value), copies));
                } catch (IllegalAccessException e) {
                    throw new PlatformException("cannot copy mutation field: " + field.getName(), e);
                }
            }
        }
        return copy;
    }

    static <T> T shallowCopy(T entity, String constructorRequirement) {
        if (entity == null) {
            return null;
        }
        T copy = newInstance(entity, constructorRequirement);
        Class<?> current = entity.getClass();
        while (current != null && !Object.class.equals(current)) {
            copyFields(entity, copy, current);
            current = current.getSuperclass();
        }
        return copy;
    }

    private static <T> T newInstance(T entity, String constructorRequirement) {
        try {
            @SuppressWarnings("unchecked")
            Constructor<T> constructor = (Constructor<T>) entity.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new PlatformException(constructorRequirement + ": "
                    + entity.getClass().getName(), e);
        }
    }

    private static void copyFields(Object source, Object target, Class<?> owner) {
        for (Field field : owner.getDeclaredFields()) {
            if (shouldSkip(field)) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(target, field.get(source));
            } catch (IllegalAccessException e) {
                throw new PlatformException("cannot copy record field: "
                        + owner.getName() + "." + field.getName(), e);
            }
        }
    }

    private static boolean shouldSkip(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers)
                || Modifier.isTransient(modifiers)
                || field.getAnnotation(Children.class) != null;
    }
}
