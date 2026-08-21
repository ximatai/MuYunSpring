package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class StaticChildResolver {
    private static final Map<Class<?>, List<ChildRule>> RULES = new ConcurrentHashMap<>();

    private StaticChildResolver() {
    }

    public static List<ChildPlan> plans(Class<?> parentModelClass) {
        return rules(parentModelClass).stream()
                .map(ChildRule::plan)
                .toList();
    }

    public static ChildPlan singlePlan(Class<?> parentModelClass) {
        return singleRule(parentModelClass).plan();
    }

    public static ChildRule singleRule(Class<?> parentModelClass) {
        if (parentModelClass == null) {
            throw new PlatformException("child parentModelClass must not be null");
        }
        List<ChildRule> rules = rules(parentModelClass);
        if (rules.isEmpty()) {
            throw new PlatformException("expected exactly one child relation plan: "
                    + parentModelClass.getName()
                    + ", actual relationCodes: [], add @Children/@ChildOf or use explicit childRelation(...)");
        }
        if (rules.size() != 1) {
            throw new PlatformException("expected exactly one child relation plan: "
                    + parentModelClass.getName()
                    + ", actual relationCodes: "
                    + rules.stream().map(rule -> rule.plan().relationCode()).toList()
                    + ", use childRelation(relationCode, ...)");
        }
        return rules.getFirst();
    }

    public static ChildPlan plan(Class<?> parentModelClass, String relationCode) {
        return rule(parentModelClass, relationCode).plan();
    }

    public static ChildRule rule(Class<?> parentModelClass, String relationCode) {
        if (parentModelClass == null) {
            throw new PlatformException("child parentModelClass must not be null");
        }
        if (relationCode == null || relationCode.isBlank()) {
            throw new PlatformException("child relationCode must not be blank");
        }
        return rules(parentModelClass).stream()
                .filter(rule -> relationCode.equals(rule.plan().relationCode()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("unknown child relationCode: "
                        + parentModelClass.getName() + "." + relationCode));
    }

    public static List<ChildRule> rules(Class<?> parentModelClass) {
        if (parentModelClass == null) {
            return List.of();
        }
        return RULES.computeIfAbsent(parentModelClass, StaticChildResolver::loadRules);
    }

    static void clearCacheForTests() {
        RULES.clear();
    }

    private static List<ChildRule> loadRules(Class<?> parentModelClass) {
        LinkedHashMap<String, ChildRule> rules = new LinkedHashMap<>();
        LinkedHashMap<String, Field> relationCodeFields = new LinkedHashMap<>();
        Class<?> current = parentModelClass;
        while (current != null && !Object.class.equals(current)) {
            for (Field field : current.getDeclaredFields()) {
                Children children = field.getAnnotation(Children.class);
                if (children == null) {
                    continue;
                }
                ChildRule rule = childrenRule(parentModelClass, field, children);
                Field previous = relationCodeFields.putIfAbsent(rule.plan().relationCode(), field);
                if (previous != null) {
                    throw new PlatformException("duplicate child relationCode: "
                            + parentModelClass.getName() + "." + rule.plan().relationCode());
                }
                rules.putIfAbsent(field.getName(), rule);
            }
            current = current.getSuperclass();
        }
        return List.copyOf(rules.values());
    }

    private static ChildRule childrenRule(Class<?> parentModelClass, Field field, Children children) {
        if (!List.class.isAssignableFrom(field.getType()) || !(field.getGenericType() instanceof ParameterizedType type)
                || !(type.getActualTypeArguments()[0] instanceof Class<?> childClass)
                || !EntityContract.class.isAssignableFrom(childClass)) {
            throw new PlatformException("@Children field must declare List<EntityContract>: "
                    + parentModelClass.getName() + "." + field.getName());
        }
        @SuppressWarnings("unchecked")
        Class<? extends EntityContract> childModel = (Class<? extends EntityContract>) childClass;
        Set<String> parentAliases = parentEntityAliases(parentModelClass);
        List<ReferencePlan> referencePlans = StaticReferenceResolver.plans(childModel);
        List<Field> foreignKeys = fields(childModel).stream()
                .filter(candidate -> candidate.getAnnotation(ChildOf.class) != null)
                .filter(candidate -> candidate.getAnnotation(ReferenceTo.class) != null)
                .filter(candidate -> referencePlans.stream().anyMatch(plan ->
                        plan.sourceField().equals(candidate.getName())
                                && parentAliases.contains(plan.target().entityAlias())))
                .toList();
        if (foreignKeys.size() != 1) {
            throw new PlatformException("@Children requires exactly one @ChildOf @ReferenceTo foreign key to "
                    + parentModelClass.getName() + " on " + childModel.getName());
        }
        Field foreignKey = foreignKeys.getFirst();
        validateChildForeignKeyField(parentModelClass, field, childModel, foreignKey.getName());
        field.setAccessible(true);
        foreignKey.setAccessible(true);
        ReferencePlan referencePlan = referencePlans.stream()
                .filter(plan -> plan.sourceField().equals(foreignKey.getName()))
                .findFirst()
                .orElseThrow();
        String parentAlias = referencePlan.target().entityAlias();
        String relationCode = children.relationCode().isBlank() ? field.getName() : children.relationCode();
        return new ChildRule(field, new ChildPlan(relationCode, parentAlias, defaultEntityAlias(childModel),
                foreignKey.getName(), true,
                referencePlan.integrity().onTargetUnavailable() == ReferenceTargetUnavailablePolicy.CASCADE_DELETE),
                childModel, foreignKey);
    }

    private static Field validateChildForeignKeyField(Class<?> parentModelClass,
                                                      Field relationField,
                                                      Class<? extends EntityContract> childModel,
                                                      String childForeignKeyField) {
        if (childForeignKeyField == null || childForeignKeyField.isBlank()) {
            throw new PlatformException("child foreign key field must not be blank: "
                    + parentModelClass.getName() + "." + relationField.getName());
        }
        Field field = childField(childModel, childForeignKeyField);
        if (!String.class.equals(field.getType())) {
            throw new PlatformException("child foreign key field must be String: "
                    + childModel.getName() + "." + childForeignKeyField);
        }
        try {
            field.setAccessible(true);
        } catch (RuntimeException e) {
            throw new PlatformException("cannot access child foreign key field: "
                    + childModel.getName() + "." + childForeignKeyField, e);
        }
        return field;
    }

    private static Field childField(Class<?> childClass, String fieldName) {
        Class<?> current = childClass;
        while (current != null && !Object.class.equals(current)) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new PlatformException("cannot find child foreign key field: "
                + childClass.getName() + "." + fieldName);
    }

    private static List<Field> fields(Class<?> modelClass) {
        List<Field> fields = new java.util.ArrayList<>();
        Class<?> current = modelClass;
        while (current != null && !Object.class.equals(current)) {
            fields.addAll(List.of(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private static String defaultEntityAlias(Class<?> modelClass) {
        String simpleName = modelClass.getSimpleName();
        if (simpleName.isBlank()) {
            throw new PlatformException("model simple name must not be blank");
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private static Set<String> parentEntityAliases(Class<?> modelClass) {
        String defaultAlias = defaultEntityAlias(modelClass);
        String snakeAlias = defaultAlias.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(java.util.Locale.ROOT);
        return defaultAlias.equals(snakeAlias) ? Set.of(defaultAlias) : Set.of(defaultAlias, snakeAlias);
    }

    public record ChildRule(Field field,
                            ChildPlan plan,
                            Class<? extends EntityContract> childModel,
                            Field childForeignKeyField) {
        @SuppressWarnings("unchecked")
        public <P extends EntityContract, C extends EntityContract> List<C> children(P parent) {
            if (parent == null) {
                return null;
            }
            try {
                return (List<C>) field.get(parent);
            } catch (IllegalAccessException e) {
                throw new PlatformException("cannot read child relation field: "
                        + parent.getClass().getName() + "." + field.getName(), e);
            }
        }

        public <P extends EntityContract, C extends EntityContract> void populate(P parent, List<C> children) {
            if (parent == null) {
                return;
            }
            try {
                field.set(parent, children);
            } catch (IllegalAccessException e) {
                throw new PlatformException("cannot write child relation field: "
                        + parent.getClass().getName() + "." + field.getName(), e);
            } catch (IllegalArgumentException e) {
                throw new PlatformException("cannot write child relation field: "
                        + parent.getClass().getName() + "." + field.getName(), e);
            }
        }

        public <C extends EntityContract> void setParentId(C child, String parentId) {
            if (child == null) {
                return;
            }
            try {
                childForeignKeyField.set(child, parentId);
            } catch (IllegalAccessException e) {
                throw new PlatformException("cannot write child foreign key field: "
                        + child.getClass().getName() + "." + plan.childForeignKeyField(), e);
            } catch (IllegalArgumentException e) {
                throw new PlatformException("cannot write child foreign key field: "
                        + child.getClass().getName() + "." + plan.childForeignKeyField(), e);
            }
        }
    }
}
