package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.SortPartition;
import net.ximatai.muyun.database.core.orm.Criteria;

final class DynamicTreeRuntime extends DynamicAbilityRuntime<DynamicTreeRecord> implements TreeAbility<DynamicTreeRecord> {
    DynamicTreeRuntime(DynamicEntityService owner) {
        super(owner, DynamicTreeRecord::new);
    }

    @Override
    public SortPartition<DynamicTreeRecord> sortPartition() {
        return new SortPartition<>() {
            @Override
            public Criteria criteriaFor(DynamicTreeRecord record) {
                return owner.sortPartition().criteriaFor(record.record());
            }

            @Override
            public void requireSamePartition(DynamicTreeRecord left, DynamicTreeRecord right) {
                owner.sortPartition().requireSamePartition(left.record(), right.record());
            }
        };
    }

    @Override
    public void validateTreeMoveTarget(DynamicTreeRecord moving, String targetParentId) {
        if (targetParentId == null || targetParentId.isBlank() || TreeAbility.ROOT_ID.equals(targetParentId)) {
            return;
        }
        DynamicRecord targetParent = owner.select(targetParentId);
        if (targetParent != null) {
            owner.validateTreeMoveBusinessPartition(moving.record(), targetParent);
        }
    }
}
