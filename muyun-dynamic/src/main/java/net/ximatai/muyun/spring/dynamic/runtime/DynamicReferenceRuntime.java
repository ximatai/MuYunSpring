package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;

import java.util.Collection;
import java.util.Map;

final class DynamicReferenceRuntime extends DynamicAbilityRuntime<DynamicTitledRecord> implements ReferenceAbility<DynamicTitledRecord> {
    DynamicReferenceRuntime(DynamicEntityService owner) {
        super(owner, DynamicTitledRecord::new);
    }

    @Override
    public ReferenceTarget referenceTarget() {
        return owner.referenceTarget();
    }

    @Override
    public DynamicTitledRecord selectReferenceRaw(String id) {
        return wrap(owner.activeRaw(id));
    }

    @Override
    public String referenceTitle(DynamicTitledRecord entity) {
        return entity == null ? null : owner.referenceTitle(entity.record());
    }

    @Override
    public Map<String, Map<String, Object>> projections(Collection<String> ids, Collection<String> fieldNames) {
        return owner.projections(ids, fieldNames);
    }

    @Override
    public PageResult<ReferenceOption> referenceOptions(ReferencePlan plan, Criteria criteria, PageRequest pageRequest) {
        return owner.referenceOptions(plan, criteria, pageRequest);
    }
}
