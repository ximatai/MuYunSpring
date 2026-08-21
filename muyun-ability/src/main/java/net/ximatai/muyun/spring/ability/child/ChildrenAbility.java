package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;

import java.util.ArrayList;
import java.util.List;

public interface ChildrenAbility<P extends EntityContract> extends CrudAbility<P> {
    default List<ChildRelation<? extends EntityContract, P>> childRelations() {
        Class<?> parentModel = requireModelClass("childRelations()");
        List<ChildRelation<? extends EntityContract, P>> relations = new ArrayList<>();
        for (StaticChildResolver.ChildRule rule : StaticChildResolver.rules(parentModel)) {
            relations.add(autoChildRelation(rule));
        }
        return List.copyOf(relations);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ChildRelation<? extends EntityContract, P> autoChildRelation(StaticChildResolver.ChildRule rule) {
        ChildAbility childAbility = PlatformAbilityRuntime.childAbilityResolver()
                .resolve(ChildAbilityRequest.forStaticModel(rule.childModel()))
                .orElseThrow(() -> new PlatformException("child ability is not registered: "
                        + rule.plan().relationCode() + " -> " + rule.childModel().getName()));
        validateChildModel(rule, childAbility);
        ChildAbility<EntityContract> typedAbility = (ChildAbility<EntityContract>) childAbility;
        return typedAbility.toChildRelation(
                rule.plan(),
                (child, parentId) -> rule.setParentId(child, parentId),
                rule::children,
                rule::populate
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    default <C extends EntityContract> ChildRelation<C, P> childRelation(ChildAbility<C> childAbility) {
        StaticChildResolver.ChildRule rule = StaticChildResolver.singleRule(
                requireModelClass("childRelation(...)")
        );
        validateChildModel(rule, childAbility);
        return childAbility.toChildRelation(
                rule.plan(),
                rule::setParentId,
                rule::children,
                rule::populate
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    default <C extends EntityContract> ChildRelation<C, P> childRelation(String relationCode,
                                                                         ChildAbility<C> childAbility) {
        StaticChildResolver.ChildRule rule = StaticChildResolver.rule(
                requireModelClass("childRelation(relationCode, ...)"),
                relationCode
        );
        validateChildModel(rule, childAbility);
        return childAbility.toChildRelation(
                rule.plan(),
                rule::setParentId,
                rule::children,
                rule::populate
        );
    }

    default void afterChildrenInsert(String id, P parent) {
        if (usesAutomaticChildRelations()) {
            for (StaticChildResolver.ChildRule rule : StaticChildResolver.rules(
                    requireModelClass("afterChildrenInsert(...)"))) {
                List<? extends EntityContract> children = rule.children(parent);
                if (children != null && !children.isEmpty()) {
                    autoChildRelation(rule).insertChildren(id, parent);
                }
            }
            return;
        }
        for (ChildRelation<? extends EntityContract, P> relation : childRelations()) {
            relation.insertChildren(id, parent);
        }
    }

    default void afterChildrenUpdate(P parent, int updated) {
        if (updated <= 0) {
            return;
        }
        if (usesAutomaticChildRelations()) {
            for (StaticChildResolver.ChildRule rule : StaticChildResolver.rules(
                    requireModelClass("afterChildrenUpdate(...)"))) {
                if (rule.children(parent) != null) {
                    autoChildRelation(rule).replaceChildren(parent.getId(), parent);
                }
            }
            return;
        }
        for (ChildRelation<? extends EntityContract, P> relation : childRelations()) {
            relation.replaceChildren(parent.getId(), parent);
        }
    }

    /**
     * Executes aggregate cascades derived from child foreign-key reference integrity
     * before the parent becomes unavailable.
     */
    default void beforeChildrenDelete(String id,
                                      P parent,
                                      DeletionContext deletionContext,
                                      DeletionNode deletionNode) {
        if (parent == null || id == null || id.isBlank()) {
            return;
        }
        deleteAutoDeleteChildren(id, deletionContext, deletionNode);
    }

    /**
     * Whether this service uses the default {@code @Children} resolver.
     * Services with a deliberately conditional or hand-built relation override this to {@code false}.
     */
    default boolean usesAutomaticChildRelations() {
        return true;
    }

    private void deleteAutoDeleteChildren(String id,
                                          DeletionContext deletionContext,
                                          DeletionNode deletionNode) {
        for (ChildRelation<? extends EntityContract, P> relation : childRelations()) {
            if (relation.isCascadeOnParentUnavailable()) {
                relation.clearChildren(id, deletionContext, deletionNode);
            }
        }
    }

    default void afterChildrenSelect(P parent) {
        for (ChildRelation<? extends EntityContract, P> relation : childRelations()) {
            relation.loadChildren(parent);
        }
    }

    private Class<?> requireModelClass(String explicitFallback) {
        Class<?> modelClass = modelClass();
        if (modelClass == null) {
            throw new PlatformException("child relation requires modelClass: "
                    + getModuleAlias()
                    + ", extend AbstractAbilityService or use " + explicitFallback);
        }
        return modelClass;
    }

    private void validateChildModel(StaticChildResolver.ChildRule rule, ChildAbility<?> childAbility) {
        Class<?> actualChildModel = childAbility.modelClass();
        if (actualChildModel == null || rule.childModel().equals(actualChildModel)) {
            return;
        }
        throw new PlatformException("child relation model mismatch: "
                + rule.plan().relationCode()
                + ", expected " + rule.childModel().getName()
                + ", actual " + actualChildModel.getName());
    }

}
