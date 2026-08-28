package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class PlatformUiConfigService extends AbstractAbilityService<PlatformUiConfig> implements
        SoftDeleteAbility<PlatformUiConfig>,
        EnableAbility<PlatformUiConfig>,
        SortAbility<PlatformUiConfig>,
        QueryAbility<PlatformUiConfig> {
    public static final String MODULE_ALIAS = "platform.ui_config";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final PlatformUiSetService uiSetService;
    private final Supplier<PlatformUiConfigFieldService> uiConfigFieldServiceProvider;

    public PlatformUiConfigService(BaseDao<PlatformUiConfig, String> uiConfigDao,
                                   PlatformUiSetService uiSetService) {
        this(uiConfigDao, uiSetService, () -> null);
    }

    @Autowired
    public PlatformUiConfigService(BaseDao<PlatformUiConfig, String> uiConfigDao,
                                   PlatformUiSetService uiSetService,
                                   ObjectProvider<PlatformUiConfigFieldService> uiConfigFieldServiceProvider) {
        this(uiConfigDao, uiSetService, uiConfigFieldServiceProvider::getIfAvailable);
    }

    PlatformUiConfigService(BaseDao<PlatformUiConfig, String> uiConfigDao,
                            PlatformUiSetService uiSetService,
                            Supplier<PlatformUiConfigFieldService> uiConfigFieldServiceProvider) {
        super(MODULE_ALIAS, PlatformUiConfig.class, uiConfigDao);
        this.uiSetService = uiSetService;
        this.uiConfigFieldServiceProvider = uiConfigFieldServiceProvider;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformUiConfig.class, java.util.List.of("title", "clientType", "published", "enabled"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("clientType"));
    }

    @Override
    public void beforeInsert(PlatformUiConfig uiConfig) {
        normalizeAndValidate(uiConfig);
        rejectDirectPublish(uiConfig);
    }

    @Override
    public void beforeUpdate(PlatformUiConfig uiConfig) {
        PlatformUiConfig existing = selectIncludingDeleted(uiConfig.getId());
        rejectDirectPublish(existing, uiConfig);
        rejectPublishedEdit(existing, uiConfig);
        normalizeAndValidate(uiConfig);
        rejectChanged(existing, uiConfig, "UI config set", PlatformUiConfig::getUiSetId);
        rejectChanged(existing, uiConfig, "UI config client", PlatformUiConfig::getClientType);
    }

    @Override
    public void beforeDelete(String id) {
        PlatformUiConfig existing = select(id);
        if (existing != null && Boolean.TRUE.equals(existing.getPublished())) {
            throw BusinessExceptions.warning("platform.ui-config.published-delete-denied",
                    "Published UI config cannot be deleted; unpublish first: " + id);
        }
        PlatformUiConfigFieldService uiConfigFieldService = uiConfigFieldServiceProvider.get();
        if (uiConfigFieldService != null && uiConfigFieldService.hasActiveFieldForUiConfig(id)) {
            throw BusinessExceptions.warning("platform.ui-config.fields-exist",
                    "UI config cannot be deleted while UI fields exist: " + id);
        }
    }

    public PlatformUiConfig requireUiConfig(String id) {
        PlatformUiConfig uiConfig = id == null || id.isBlank() ? null : select(id);
        if (uiConfig == null) {
            throw BusinessExceptions.warning("platform.ui-config.not-found",
                    "UI config requires existing config: " + id);
        }
        return uiConfig;
    }

    public List<PlatformUiConfig> listByUiSetIds(List<String> uiSetIds) {
        if (uiSetIds == null || uiSetIds.isEmpty()) {
            return List.of();
        }
        return list(enabledCriteria(Criteria.of().in("uiSetId", uiSetIds)), ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public PlatformUiConfig findByUiSetAndClient(String uiSetId, PlatformUiClientType clientType) {
        if (uiSetId == null || uiSetId.isBlank() || clientType == null) {
            return null;
        }
        return findOne(Criteria.of()
                .eq("uiSetId", uiSetId)
                .eq("clientType", clientType));
    }

    public List<PlatformUiConfig> listPublishedByUiSetIds(List<String> uiSetIds) {
        if (uiSetIds == null || uiSetIds.isEmpty()) {
            return List.of();
        }
        return list(enabledCriteria(Criteria.of()
                        .in("uiSetId", uiSetIds)
                        .eq("published", Boolean.TRUE)),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    boolean hasActiveConfigForUiSet(String uiSetId) {
        return uiSetId != null && !uiSetId.isBlank() && count(Criteria.of().eq("uiSetId", uiSetId)) > 0;
    }

    boolean hasPublishedConfigForUiSet(String uiSetId) {
        return uiSetId != null && !uiSetId.isBlank()
                && count(Criteria.of().eq("uiSetId", uiSetId).eq("published", Boolean.TRUE)) > 0;
    }

    private void normalizeAndValidate(PlatformUiConfig uiConfig) {
        PlatformUiSet uiSet = uiSetService.requireUiSet(uiConfig.getUiSetId());
        if (uiConfig.getClientType() == null) {
            uiConfig.setClientType(PlatformUiClientType.WEB);
        }
        if (uiConfig.getPublished() == null) {
            uiConfig.setPublished(Boolean.FALSE);
        }
        if (uiConfig.getTitle() == null || uiConfig.getTitle().isBlank()) {
            uiConfig.setTitle(uiSet.getTitle() + "-" + uiConfig.getClientType().name());
        }
        rejectDuplicate(uiConfig, Criteria.of()
                        .eq("uiSetId", uiSet.getId())
                        .eq("clientType", uiConfig.getClientType()),
                "UI config client must be unique in UI set: " + uiSet.getId() + "." + uiConfig.getClientType());
        uiConfig.setUiSetId(uiSet.getId());
    }

    private void rejectPublishedEdit(PlatformUiConfig existing, PlatformUiConfig updated) {
        if (existing == null || !Boolean.TRUE.equals(existing.getPublished())) {
            return;
        }
        if (Boolean.FALSE.equals(updated.getPublished())
                && Objects.equals(existing.getUiSetId(), updated.getUiSetId())
                && Objects.equals(existing.getClientType(), updated.getClientType())
                && Objects.equals(existing.getLayoutJson(), updated.getLayoutJson())
                && Objects.equals(existing.getTitle(), updated.getTitle())
                && Objects.equals(existing.getEnabled(), updated.getEnabled())
                && Objects.equals(existing.getSortOrder(), updated.getSortOrder())) {
            return;
        }
        throw BusinessExceptions.warning("platform.ui-config.published-edit-denied",
                "Published UI config cannot be edited; unpublish first: " + existing.getId());
    }


    private void rejectDirectPublish(PlatformUiConfig uiConfig) {
        if (Boolean.TRUE.equals(uiConfig.getPublished()) && !PlatformPageConfigPublishContext.active()) {
            throw BusinessExceptions.warning("platform.ui-config.direct-publish-denied",
                    "UI config can only be published through publish service: " + uiConfig.getId());
        }
    }

    private void rejectDirectPublish(PlatformUiConfig existing, PlatformUiConfig updated) {
        if (PlatformPageConfigPublishContext.active() || updated == null || !Boolean.TRUE.equals(updated.getPublished())) {
            return;
        }
        if (existing == null || !Boolean.TRUE.equals(existing.getPublished())) {
            throw BusinessExceptions.warning("platform.ui-config.direct-publish-denied",
                    "UI config can only be published through publish service: " + updated.getId());
        }
    }
}
