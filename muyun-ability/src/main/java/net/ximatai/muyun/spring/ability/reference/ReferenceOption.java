package net.ximatai.muyun.spring.ability.reference;

/** One candidate exposed by a reference picker; {@code id} is always the target record id. */
public record ReferenceOption(String id, String title) {
}
