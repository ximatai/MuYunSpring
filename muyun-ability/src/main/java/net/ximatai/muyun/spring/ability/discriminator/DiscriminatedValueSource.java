package net.ximatai.muyun.spring.ability.discriminator;

/** The bounded ways a discriminator case can provide the value of its owning field. */
public enum DiscriminatedValueSource {
    FIXED,
    FIELD,
    REFERENCE
}
