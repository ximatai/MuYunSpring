package net.ximatai.muyun.spring.ability.discriminator;

import net.ximatai.muyun.spring.ability.reference.ReferencePlan;

/** Runtime branch contract, shared by static annotations and dynamic metadata. */
public record DiscriminatedValueCasePlan(String when, DiscriminatedValueSource source,
                                         String fixedValue, String sourceField, ReferencePlan reference) {
    public DiscriminatedValueCasePlan {
        if (when == null || when.isBlank()) throw new IllegalArgumentException("discriminator case value must not be blank");
        if (source == null) throw new IllegalArgumentException("discriminator case source must not be null");
        if (source == DiscriminatedValueSource.FIXED && (fixedValue == null || fixedValue.isBlank()))
            throw new IllegalArgumentException("fixed discriminator case requires fixedValue");
        if (source == DiscriminatedValueSource.FIELD && (sourceField == null || sourceField.isBlank()))
            throw new IllegalArgumentException("field discriminator case requires sourceField");
        if (source == DiscriminatedValueSource.REFERENCE && reference == null)
            throw new IllegalArgumentException("reference discriminator case requires reference");
        if (source != DiscriminatedValueSource.REFERENCE && reference != null)
            throw new IllegalArgumentException("only reference discriminator cases may declare a reference");
    }
}
