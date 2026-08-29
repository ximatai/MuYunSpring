package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class PlatformPageDefinitionService extends AbstractAbilityService<PlatformPageDefinition> implements
        SoftDeleteAbility<PlatformPageDefinition>,
        EnableAbility<PlatformPageDefinition>,
        SortAbility<PlatformPageDefinition>,
        QueryAbility<PlatformPageDefinition> {
    public static final String MODULE_ALIAS = "platform.page_definition";

    private final PlatformModuleService moduleService;
    private final ModuleMetadataRelationService relationService;
    private final PublishedPageExecutionCoordinator pageExecutionCoordinator;

    public PlatformPageDefinitionService(BaseDao<PlatformPageDefinition, String> pageDao,
                                         PlatformModuleService moduleService,
                                         ModuleMetadataRelationService relationService) {
        this(pageDao, moduleService, relationService, PublishedPageExecutionCoordinator.noop());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformPageDefinitionService(BaseDao<PlatformPageDefinition, String> pageDao,
                                         PlatformModuleService moduleService,
                                         ModuleMetadataRelationService relationService,
                                         ObjectProvider<PublishedPageExecutionCoordinator> pageExecutionCoordinator) {
        this(pageDao, moduleService, relationService,
                pageExecutionCoordinator == null ? PublishedPageExecutionCoordinator.noop()
                        : pageExecutionCoordinator.getIfAvailable(PublishedPageExecutionCoordinator::noop));
    }

    PlatformPageDefinitionService(BaseDao<PlatformPageDefinition, String> pageDao,
                                  PlatformModuleService moduleService,
                                  ModuleMetadataRelationService relationService,
                                  PublishedPageExecutionCoordinator pageExecutionCoordinator) {
        super(MODULE_ALIAS, PlatformPageDefinition.class, pageDao);
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.pageExecutionCoordinator = pageExecutionCoordinator == null
                ? PublishedPageExecutionCoordinator.noop() : pageExecutionCoordinator;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformPageDefinition.class,
                java.util.List.of("id", "moduleAlias", "alias", "contractType", "mainRelationId", "title",
                        "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("alias"));
    }

    @Override
    public void beforeInsert(PlatformPageDefinition page) {
        normalizeAndValidate(page);
    }

    @Override
    public void beforeUpdate(PlatformPageDefinition page) {
        PlatformPageDefinition existing = selectIncludingDeleted(page.getId());
        normalizeAndValidate(page);
        rejectChanged(existing, page, "Page moduleAlias", PlatformPageDefinition::getModuleAlias);
        rejectChanged(existing, page, "Page alias", PlatformPageDefinition::getAlias);
        rejectChanged(existing, page, "Page contract type", PlatformPageDefinition::getContractType);
        rejectChanged(existing, page, "Page main relation", PlatformPageDefinition::getMainRelationId);
    }

    @Override
    public void afterUpdate(PlatformPageDefinition page, int updated) {
        refreshPublishedPageExecution(page);
    }

    @Override
    public void afterDelete(String id, PlatformPageDefinition page, int deleted) {
        refreshPublishedPageExecution(page);
    }

    public PlatformPageDefinition requireVisiblePage(String id) {
        PlatformPageDefinition page = id == null || id.isBlank() ? null : select(id);
        if (page == null && id != null && !id.isBlank() && TenantContext.currentTenantId().isPresent()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve global page definition")) {
                page = select(id);
            }
        }
        if (page == null) {
            throw BusinessExceptions.warning("platform.page-definition.not-found",
                    "Page definition requires existing page: " + id);
        }
        return page;
    }

    /** Resolves the stable page identity before client/scope-specific presentation resolution. */
    public Optional<PlatformPageDefinition> resolveVisiblePage(String moduleAlias, String alias) {
        String normalizedModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        String normalizedAlias = PlatformNameRules.requireIdentifier(alias, "pageAlias");
        Criteria criteria = Criteria.of().eq("moduleAlias", normalizedModuleAlias).eq("alias", normalizedAlias);
        PlatformPageDefinition page = list(enabledCriteria(criteria)).stream().findFirst().orElse(null);
        if (page == null && TenantContext.currentTenantId().isPresent()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve global page definition")) {
                page = list(enabledCriteria(criteria)).stream().findFirst().orElse(null);
            }
        }
        return Optional.ofNullable(page);
    }

    /** Resolves the global page identity for a source that is explicitly global by contract. */
    public Optional<PlatformPageDefinition> resolveGlobalPage(String moduleAlias, String alias) {
        String normalizedModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        String normalizedAlias = PlatformNameRules.requireIdentifier(alias, "pageAlias");
        Criteria criteria = Criteria.of().eq("moduleAlias", normalizedModuleAlias)
                .eq("alias", normalizedAlias)
                .isNull(StandardEntitySchema.TENANT_ID_FIELD);
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve global page definition")) {
            return Optional.ofNullable(list(enabledCriteria(criteria)).stream().findFirst().orElse(null));
        }
    }

    private void normalizeAndValidate(PlatformPageDefinition page) {
        if (!TenantContext.isSystem()) {
            throw BusinessExceptions.warning("platform.page-definition.global-system-context-required",
                    "Stable page definition requires system context; tenant and organization differences belong to variants");
        }
        String moduleAlias = PlatformNameRules.requireModuleAlias(page.getModuleAlias());
        if (moduleService.resolveVisibleModule(moduleAlias) == null) {
            throw BusinessExceptions.warning("platform.page-definition.module-not-found",
                    "Page definition requires existing module: " + moduleAlias);
        }
        String alias = PlatformNameRules.requireIdentifier(page.getAlias(), "pageAlias");
        if (page.getContractType() == null) {
            throw BusinessExceptions.warning("platform.page-definition.contract-type-required",
                    "Page definition contract type must not be null");
        }
        ModuleMetadataRelation mainRelation = requireRelation(page.getMainRelationId());
        if (!moduleAlias.equals(mainRelation.getModuleAlias()) || mainRelation.getRelationRole() != RelationRole.MAIN) {
            throw BusinessExceptions.warning("platform.page-definition.main-relation-invalid",
                    "Page definition main relation must be the module MAIN relation: " + page.getMainRelationId());
        }
        page.setModuleAlias(moduleAlias);
        page.setAlias(alias);
        page.setMainRelationId(mainRelation.getId());
        page.setTenantId(null);
        if (page.getTitle() == null || page.getTitle().isBlank()) {
            page.setTitle(alias);
        }
        rejectDuplicate(page, Criteria.of().eq("moduleAlias", moduleAlias).eq("alias", alias),
                "Page alias must be unique in module: " + moduleAlias + "." + alias);
    }

    private ModuleMetadataRelation requireRelation(String relationId) {
        if (relationId == null || relationId.isBlank()) {
            throw BusinessExceptions.warning("platform.page-definition.main-relation-required",
                    "Page definition requires mainRelationId");
        }
        ModuleMetadataRelation relation = relationService.select(relationId.trim());
        if (relation == null && TenantContext.currentTenantId().isPresent()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve global module metadata relation")) {
                relation = relationService.select(relationId.trim());
            }
        }
        if (relation == null) {
            throw BusinessExceptions.warning("platform.page-definition.main-relation-not-found",
                    "Page definition requires existing main relation: " + relationId);
        }
        return relation;
    }

    private void refreshPublishedPageExecution(PlatformPageDefinition page) {
        if (page != null && page.getModuleAlias() != null && !page.getModuleAlias().isBlank()) {
            pageExecutionCoordinator.prepareAfterPublishedConfigurationChange(page.getModuleAlias());
        }
    }
}
