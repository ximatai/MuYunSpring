package net.ximatai.muyun.spring.ability.reference;

/**
 * A readable target field that can be selected for the displayed label of a reference.
 *
 * <p>{@code defaultField} identifies the target's declared title field, or the conventional
 * {@code title} field when no explicit title declaration exists.  It is a presentation default,
 * not a requirement that callers must use that field.</p>
 */
public record ReferenceCandidateField(String fieldName, boolean defaultField) {
}
