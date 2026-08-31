package net.ximatai.muyun.spring.ability.reference;

/**
 * A target field's suitability as the persisted value of a reference.
 *
 * <p>The descriptor is intentionally small and model-facing so metadata governance can validate
 * a static target without importing ORM reflection details.  A non-primary candidate key is only
 * usable when the target can prove both readability and uniqueness.</p>
 */
public record ReferenceCandidateKey(String fieldName, boolean readable, boolean unique) {
    public boolean usable() {
        return readable && unique;
    }
}
