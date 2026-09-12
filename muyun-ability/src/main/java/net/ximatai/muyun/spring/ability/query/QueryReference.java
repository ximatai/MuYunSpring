package net.ximatai.muyun.spring.ability.query;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;

/**
 * Source-neutral selection contract for a query value that identifies a record in another module.
 *
 * <p>Unlike a model reference this does not imply persistence or referential-integrity ownership.
 * It lets read-only facts, such as an audit actor, use the same record picker as a persisted
 * reference while their query endpoint remains responsible for interpreting the selected id.</p>
 */
public record QueryReference(String targetModuleAlias,
                             ReferenceCardinality cardinality,
                             String labelField) {
    public QueryReference {
        if (targetModuleAlias == null || targetModuleAlias.isBlank()) {
            throw new IllegalArgumentException("query reference target module alias must not be blank");
        }
        targetModuleAlias = targetModuleAlias.trim();
        cardinality = cardinality == null ? ReferenceCardinality.ONE : cardinality;
        labelField = labelField == null || labelField.isBlank() ? null : labelField.trim();
    }
}
