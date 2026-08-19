package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.List;

public final class StaticReferenceCompiler {
    private StaticReferenceCompiler() {
    }

    public static List<StaticReferenceDefinition> compile(Class<?> modelClass) {
        if (modelClass == null || modelClass == Object.class) {
            return List.of();
        }
        return StaticReferenceResolver.rules(modelClass).stream()
                .filter(rule -> rule.cardinality() == ReferenceCardinality.ONE)
                .map(rule -> new StaticReferenceDefinition(
                        referenceCode(rule.plan().sourceField()),
                        rule.plan().sourceField(),
                        rule.target().qualifiedName()
                ))
                .toList();
    }

    private static String referenceCode(String fieldName) {
        if (fieldName.endsWith("Id") && fieldName.length() > 2) {
            return identifierFromReferenceField(fieldName.substring(0, fieldName.length() - 2));
        }
        if (fieldName.endsWith("Alias") && fieldName.length() > "Alias".length()) {
            return identifierFromReferenceField(fieldName.substring(0, fieldName.length() - "Alias".length()));
        }
        return PlatformNameRules.requireIdentifier(fieldName, "referenceCode");
    }

    private static String identifierFromReferenceField(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}
