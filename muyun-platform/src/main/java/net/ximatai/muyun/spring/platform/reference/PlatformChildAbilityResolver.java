package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbilityRequest;
import net.ximatai.muyun.spring.ability.child.ChildAbilityResolver;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.child.StaticChildResolver;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.Objects;
import java.util.Optional;

/** Resolves static child abilities from the platform's single static ability catalog. */
public final class PlatformChildAbilityResolver implements ChildAbilityResolver {
    private final StaticAbilityCatalog abilities;

    public PlatformChildAbilityResolver(StaticAbilityCatalog abilities) {
        this.abilities = Objects.requireNonNull(abilities, "abilities must not be null");
        validateChildDeclarations();
    }

    @Override
    public Optional<ChildAbility<?>> resolve(ChildAbilityRequest child) {
        if (child == null || child.staticModel() == null) {
            return Optional.empty();
        }
        return abilities.findByModel(child.staticModel())
                .filter(ChildAbility.class::isInstance)
                .map(ChildAbility.class::cast);
    }

    private void validateChildDeclarations() {
        for (var parentAbility : abilities.abilities()) {
            if (!(parentAbility instanceof ChildrenAbility<?> childrenAbility)
                    || !childrenAbility.usesAutomaticChildRelations()) {
                continue;
            }
            for (StaticChildResolver.ChildRule rule : StaticChildResolver.rules(parentAbility.modelClass())) {
                ChildAbility<?> childAbility = resolve(ChildAbilityRequest.forStaticModel(rule.childModel()))
                        .orElse(null);
                if (childAbility == null) {
                    throw new PlatformException("@Children child service is not registered: "
                            + parentAbility.modelClass().getName() + "." + rule.plan().relationCode()
                            + " -> " + rule.childModel().getName());
                }
                if (childAbility instanceof DataScopeAbility<?>) {
                    throw new PlatformException("automatic @Children aggregate reads do not support independent "
                            + "DataScopeAbility: " + parentAbility.modelClass().getName() + "."
                            + rule.plan().relationCode() + ", use an explicitly scoped child reader");
                }
            }
        }
    }

}
