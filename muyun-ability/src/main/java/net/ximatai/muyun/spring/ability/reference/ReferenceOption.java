package net.ximatai.muyun.spring.ability.reference;

/**
 * One candidate exposed by a reference picker.
 *
 * <p>{@code id} is intentionally the value persisted by the source reference.  It is not
 * necessarily the target record primary key: a configured candidate key such as {@code code}
 * may be stored instead.  {@code recordId} remains available internally for path projections.
 * The two-argument constructor preserves the legacy id-primary-key contract.</p>
 */
public record ReferenceOption(String id, String title, String recordId) {
    public ReferenceOption(String id, String title) {
        this(id, title, id);
    }
}
