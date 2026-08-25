package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueCasePlan;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValuePlan;

import java.util.List;
import java.util.Set;

/** Dynamic metadata counterpart of a static {@code @DiscriminatedValue} field declaration. */
public record EntityDiscriminatedValueDefinition(String sourceEntityAlias, String valueField,
                                                 String discriminatorField, Set<String> discriminatorValues,
                                                 List<DiscriminatedValueCasePlan> cases) {
    public EntityDiscriminatedValueDefinition {
        discriminatorValues = discriminatorValues == null ? Set.of() : Set.copyOf(discriminatorValues);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public DiscriminatedValuePlan plan() {
        return new DiscriminatedValuePlan(valueField, discriminatorField, discriminatorValues, cases);
    }
}
