package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class MetadataViewService extends AbstractAbilityService<MetadataView> implements
        SoftDeleteAbility<MetadataView>,
        EnableAbility<MetadataView>,
        SortAbility<MetadataView>,
        QueryAbility<MetadataView> {
    public static final String MODULE_ALIAS = "platform.metadata_view";

    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ModuleMetadataRelationService relationService;
    private final PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator;

    public MetadataViewService(BaseDao<MetadataView, String> viewDao,
                               ModuleMetadataRelationService relationService,
                               Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        super(MODULE_ALIAS, MetadataView.class, viewDao);
        this.relationService = Objects.requireNonNull(relationService, "relationService");
        this.runtimeRefreshCoordinator = runtimeRefreshCoordinator.orElse(null);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, MetadataView.class, java.util.List.of("id", "relationId", "viewType", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(MetadataView view) {
        normalizeAndValidate(view);
    }

    @Override
    public void beforeUpdate(MetadataView view) {
        normalizeAndValidate(view);
    }

    @Override
    public void afterChanged(MetadataView view) {
        if (runtimeRefreshCoordinator != null) {
            runtimeRefreshCoordinator.refreshByMetadataView(view);
        }
    }

    public List<MetadataView> listByRelationIds(List<String> relationIds) {
        if (relationIds == null || relationIds.isEmpty()) {
            return List.of();
        }
        Criteria criteria = Criteria.of();
        criteria.in("relationId", relationIds);
        criteria.eq("enabled", Boolean.TRUE);
        return list(criteria, ALL, Sort.asc("sortOrder"));
    }

    private void normalizeAndValidate(MetadataView view) {
        ModuleMetadataRelation relation = view.getRelationId() == null || view.getRelationId().isBlank()
                ? null
                : relationService.select(view.getRelationId());
        if (relation == null) {
            throw new PlatformException("Metadata view requires existing relation: " + view.getRelationId());
        }
        if (view.getViewType() != null && (view.getTitle() == null || view.getTitle().isBlank())) {
            view.setTitle(defaultTitle(view));
        }
        rejectDuplicate(view, Criteria.of()
                        .eq("relationId", view.getRelationId())
                        .eq("viewType", view.getViewType()),
                "metadata view must be unique in relation: " + view.getRelationId() + "." + view.getViewType());
    }

    private String defaultTitle(MetadataView view) {
        return switch (view.getViewType()) {
            case LIST -> "列表";
            case FORM -> "表单";
        };
    }
}
