package net.ximatai.muyun.spring.ability.discriminator;

import java.util.List;
import java.util.Set;

/** Source-independent description of a discriminator-controlled field. */
public record DiscriminatedValuePlan(String valueField, String discriminatorField,
                                    Set<String> discriminatorValues, List<DiscriminatedValueCasePlan> cases) {
    public DiscriminatedValuePlan {
        if (valueField == null || valueField.isBlank()) throw new IllegalArgumentException("discriminated value field must not be blank");
        if (discriminatorField == null || discriminatorField.isBlank()) throw new IllegalArgumentException("discriminator field must not be blank");
        discriminatorValues = discriminatorValues == null ? Set.of() : Set.copyOf(discriminatorValues);
        if (discriminatorValues.isEmpty()) throw new IllegalArgumentException("discriminator values must not be empty");
        cases = cases == null ? List.of() : List.copyOf(cases);
        if (cases.isEmpty()) throw new IllegalArgumentException("discriminated value requires cases");
        if (cases.stream().map(DiscriminatedValueCasePlan::when).distinct().count() != cases.size())
            throw new IllegalArgumentException("duplicate discriminated value case");
        if (!cases.stream().map(DiscriminatedValueCasePlan::when).collect(java.util.stream.Collectors.toSet())
                .equals(discriminatorValues)) throw new IllegalArgumentException("discriminated value cases must match discriminator values");
    }

    public DiscriminatedValueCasePlan caseFor(Object value) {
        String code = value instanceof net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum option
                ? option.getCode() : value == null ? null : String.valueOf(value);
        return cases.stream().filter(candidate -> candidate.when().equals(code)).findFirst().orElse(null);
    }
}
