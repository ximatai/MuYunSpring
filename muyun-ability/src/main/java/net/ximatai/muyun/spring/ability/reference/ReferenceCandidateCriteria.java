package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.SqlRawCondition;

import java.util.List;
import java.util.Map;

/** Compiles source-field candidate dependencies into the common reference query contract. */
public final class ReferenceCandidateCriteria {
    private ReferenceCandidateCriteria() {
    }

    public static Criteria from(List<ReferenceCandidateDependency> dependencies, Map<String, Object> sourceValues) {
        Criteria criteria = Criteria.of();
        Map<String, Object> values = sourceValues == null ? Map.of() : sourceValues;
        for (ReferenceCandidateDependency dependency : dependencies == null ? List.<ReferenceCandidateDependency>of() : dependencies) {
            Object value = values.get(dependency.sourceField());
            if (isBlank(value)) {
                if (dependency.required()) {
                    return criteria.raw(SqlRawCondition.of("1 = 0", Map.of()));
                }
                continue;
            }
            criteria.eq(dependency.targetField(), value);
        }
        return criteria;
    }

    private static boolean isBlank(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }
}
