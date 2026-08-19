package net.ximatai.muyun.spring.common.formula;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Versioned, source-neutral program executed by local FormulaEngine implementations.
 *
 * <p>Programs intentionally contain no expression text: text is authoring/diagnostic data held by its owning
 * descriptor, while this object is the executable cross-engine contract.</p>
 */
public record FormulaProgram(
        int schemaVersion,
        FormulaExecutionProfile profile,
        FormulaNode root,
        Set<String> referencedFields
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public FormulaProgram {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported FormulaProgram schema version: " + schemaVersion);
        }
        if (profile == null || root == null) {
            throw new IllegalArgumentException("FormulaProgram profile and root are required");
        }
        // Field order is semantically irrelevant, but retaining compiler discovery order makes descriptor JSON
        // deterministic for caches, snapshots and the shared cross-engine compatibility vectors.
        referencedFields = referencedFields == null || referencedFields.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(referencedFields));
    }
}
