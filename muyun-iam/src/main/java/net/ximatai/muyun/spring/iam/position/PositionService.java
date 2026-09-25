package net.ximatai.muyun.spring.iam.position;

import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import org.springframework.stereotype.Service;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class PositionService extends TenantActiveScopedService<Position> implements
        SoftDeleteAbility<Position>,
        EnableAbility<Position>,
        SortAbility<Position>,
        ReferenceAbility<Position>,
        QueryAbility<Position> {
    public static final String MODULE_ALIAS = "iam.position";

    public PositionService(PositionDao positionDao,
                           ActiveTenantVerifier activeTenantVerifier) {
        super(MODULE_ALIAS, Position.class, positionDao, activeTenantVerifier);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, Position.class, java.util.List.of("id", "categoryId", "code", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("title"));
    }

}
