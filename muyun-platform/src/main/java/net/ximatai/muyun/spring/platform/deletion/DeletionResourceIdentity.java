package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionRecoveryAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;

/** The same stable identity is used when recording and resolving a deletion resource. */
record DeletionResourceIdentity(String moduleAlias, String entityAlias) {
    static DeletionResourceIdentity of(CrudAbility<?> ability) {
        String entityAlias = ability instanceof DeletionRecoveryAbility<?> recovery
                ? recovery.getDeletionEntityAlias() : ReferenceTargets.of(ability).entityAlias();
        String moduleAlias = ability.getModuleAlias();
        if (moduleAlias == null || moduleAlias.isBlank() || entityAlias == null || entityAlias.isBlank()) {
            throw new IllegalArgumentException("deletion resource requires stable module and entity aliases");
        }
        return new DeletionResourceIdentity(moduleAlias, entityAlias);
    }

    static DeletionResourceIdentity from(DeletionEntry entry) {
        return new DeletionResourceIdentity(entry.getResourceModuleAlias(), entry.getResourceEntityAlias());
    }
}
