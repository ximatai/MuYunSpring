package net.ximatai.muyun.spring.common.model.constraint;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StaticTenantUniqueConstraints {
    private StaticTenantUniqueConstraints() {
    }

    public static List<TenantUniqueConstraintDefinition> resolve(Class<?> modelClass) {
        if (modelClass == null) {
            return List.of();
        }
        Map<TenantUniqueConstraintDefinition.Identity, TenantUniqueConstraintDefinition> constraints = new LinkedHashMap<>();
        Arrays.stream(modelClass.getAnnotationsByType(TenantUniqueConstraint.class))
                .map(annotation -> new TenantUniqueConstraintDefinition(List.of(annotation.fields()), annotation.message()))
                .forEach(constraint -> constraints.put(constraint.identity(), constraint));
        ConditionalIndexDefinition.from(modelClass).stream().filter(ConditionalIndexDefinition::unique)
                .map(ConditionalIndexDefinition::uniqueConstraint)
                .forEach(constraint -> constraints.put(constraint.identity(), constraint));
        Class<?> type = modelClass;
        while (type != null && type != Object.class) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                net.ximatai.muyun.database.core.annotation.Column column =
                        field.getAnnotation(net.ximatai.muyun.database.core.annotation.Column.class);
                net.ximatai.muyun.database.core.annotation.Indexed indexed =
                        field.getAnnotation(net.ximatai.muyun.database.core.annotation.Indexed.class);
                if ((column != null && column.unique()) || (indexed != null && indexed.unique())) {
                    var constraint = new TenantUniqueConstraintDefinition(List.of(field.getName()), "");
                    constraints.putIfAbsent(constraint.identity(), constraint);
                }
            }
            type = type.getSuperclass();
        }
        return List.copyOf(constraints.values());
    }
}
