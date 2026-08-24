package net.ximatai.muyun.spring.ability.query;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A source-neutral declaration of one non-form query criterion. */
public record ExternalQueryCriterionDefinition(String key,
                                               QueryValueType valueType,
                                               ExternalQueryValueSource valueSource) {
    public ExternalQueryCriterionDefinition {
        key = PlatformNameRules.requireFieldName(key, "external query key");
        if (valueType == null) {
            throw new IllegalArgumentException("external query value type must not be null");
        }
        if (valueSource == null) {
            throw new IllegalArgumentException("external query value source must not be null");
        }
    }
}
