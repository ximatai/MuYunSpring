package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.SystemStandardBusinessService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.GlobalScopedAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public class PlatformPageDefinitionService extends SystemStandardBusinessService<PlatformPageDefinition> implements
        GlobalScopedAbility<PlatformPageDefinition>,
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
                Objects.requireNonNull(pageExecutionCoordinator, "pageExecutionCoordinator provider must not be null")
                        .getIfAvailable(PublishedPageExecutionCoordinator::noop));
    }

    PlatformPageDefinitionService(BaseDao<PlatformPageDefinition, String> pageDao,
                                  PlatformModuleService moduleService,
                                  ModuleMetadataRelationService relationService,
                                  PublishedPageExecutionCoordinator pageExecutionCoordinator) {
        super(MODULE_ALIAS, PlatformPageDefinition.class, pageDao);
        this.moduleService = Objects.requireNonNull(moduleService, "moduleService must not be null");
        this.relationService = Objects.requireNonNull(relationService, "relationService must not be null");
        this.pageExecutionCoordinator = Objects.requireNonNull(pageExecutionCoordinator, "pageExecutionCoordinator must not be null");
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
    protected void validateBeforeUpdate(PlatformPageDefinition page, PlatformPageDefinition existing) {
        rejectChangedText(existing, page, TextReference.code(
                "platform.rejection.platform-page-definition.page-modulealias-cannot-be-changed",
                "Page moduleAlias cannot be changed"), PlatformPageDefinition::getModuleAlias);
        rejectChangedText(existing, page, TextReference.code(
                "platform.rejection.platform-page-definition.page-alias-cannot-be-changed",
                "Page alias cannot be changed"), PlatformPageDefinition::getAlias);
        rejectChangedText(existing, page, TextReference.code(
                "platform.rejection.platform-page-definition.page-contract-type-cannot-be-changed",
                "Page contract type cannot be changed"), PlatformPageDefinition::getContractType);
        rejectChangedText(existing, page, TextReference.code(
                "platform.rejection.platform-page-definition.page-main-relation-cannot-be-changed",
                "Page main relation cannot be changed"), PlatformPageDefinition::getMainRelationId);
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
        if (page == null) {
            throw BusinessExceptions.warningText("platform.page-definition.not-found", TextReference.code(
                    "platform.rejection.platform-page-definition.page-definition-requires-existing-page",
                    "Page definition requires existing page: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        return page;
    }

    /** Resolves the stable page identity before client/scope-specific presentation resolution. */
    public Optional<PlatformPageDefinition> resolveVisiblePage(String moduleAlias, String alias) {
        return resolveGlobalPage(moduleAlias, alias);
    }

    /** Resolves the global page identity for a source that is explicitly global by contract. */
    public Optional<PlatformPageDefinition> resolveGlobalPage(String moduleAlias, String alias) {
        String normalizedModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        String normalizedAlias = PlatformNameRules.requireIdentifier(alias, "pageAlias");
        Criteria criteria = Criteria.of().eq("moduleAlias", normalizedModuleAlias)
                .eq("alias", normalizedAlias)
                .isNull(StandardEntitySchema.TENANT_ID_FIELD);
        return list(enabledCriteria(criteria)).stream().findFirst();
    }

    @Override
    protected void validateBeforeSave(PlatformPageDefinition page) {
        String moduleAlias = PlatformNameRules.requireModuleAlias(page.getModuleAlias());
        if (moduleService.resolveVisibleModule(moduleAlias) == null) {
            throw BusinessExceptions.warningText("platform.page-definition.module-not-found", TextReference.code(
                    "platform.rejection.platform-page-definition.page-definition-requires-existing-module",
                    "Page definition requires existing module: {value1}"), Map.of("value1", String.valueOf(moduleAlias)));
        }
        String alias = PlatformNameRules.requireIdentifier(page.getAlias(), "pageAlias");
        if (page.getContractType() == null) {
            throw BusinessExceptions.warningText("platform.page-definition.contract-type-required", TextReference.code(
                    "platform.rejection.platform-page-definition.page-definition-contract-type-must-not-be-null",
                    "Page definition contract type must not be null"));
        }
        ModuleMetadataRelation mainRelation = requireRelation(page.getMainRelationId());
        if (!moduleAlias.equals(mainRelation.getModuleAlias()) || mainRelation.getRelationRole() != RelationRole.MAIN) {
            throw BusinessExceptions.warningText("platform.page-definition.main-relation-invalid", TextReference.code(
                    "platform.rejection.platform-page-definition.page-definition-main-relation-must-be-the-module-main-relation",
                    "Page definition main relation must be the module MAIN relation: {value1}"), Map.of("value1", String.valueOf(page.getMainRelationId())));
        }
        page.setModuleAlias(moduleAlias);
        page.setAlias(alias);
        page.setMainRelationId(mainRelation.getId());
        page.setTenantId(null);
        if (page.getTitle() == null || page.getTitle().isBlank()) {
            page.setTitle(alias);
        }
        rejectDuplicate(page, Criteria.of().eq("moduleAlias", moduleAlias).eq("alias", alias), () -> PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.platform-page-definition.page-alias-must-be-unique-in-module",
                "Page alias must be unique in module: {value1}.{value2}"), Map.of("value1", String.valueOf(moduleAlias), "value2", String.valueOf(alias))));
    }

    private ModuleMetadataRelation requireRelation(String relationId) {
        if (relationId == null || relationId.isBlank()) {
            throw BusinessExceptions.warningText("platform.page-definition.main-relation-required", TextReference.code(
                    "platform.rejection.platform-page-definition.page-definition-requires-mainrelationid",
                    "Page definition requires mainRelationId"));
        }
        ModuleMetadataRelation relation = relationService.select(relationId.trim());
        if (relation == null) {
            throw BusinessExceptions.warningText("platform.page-definition.main-relation-not-found", TextReference.code(
                    "platform.rejection.platform-page-definition.page-definition-requires-existing-main-relation",
                    "Page definition requires existing main relation: {value1}"), Map.of("value1", String.valueOf(relationId)));
        }
        return relation;
    }

    private void refreshPublishedPageExecution(PlatformPageDefinition page) {
        if (page != null && page.getModuleAlias() != null && !page.getModuleAlias().isBlank()) {
            pageExecutionCoordinator.prepareAfterPublishedConfigurationChange(page.getModuleAlias());
        }
    }
}
