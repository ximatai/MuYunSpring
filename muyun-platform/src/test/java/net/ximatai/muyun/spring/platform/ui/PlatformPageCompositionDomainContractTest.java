package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformPageCompositionDomainContractTest {
    private final TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
    private final TestMemoryDao<Metadata> metadataDao = new TestMemoryDao<>();
    private final TestMemoryDao<ModuleMetadataRelation> relationDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformPageDefinition> pageDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformPresentationVariant> variantDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformPresentationRevision> revisionDao = new TestMemoryDao<>();

    private final PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
    private final MetadataService metadataService = new MetadataService(metadataDao);
    private final ModuleMetadataRelationService relationService =
            new ModuleMetadataRelationService(relationDao, moduleService, metadataService);
    private final PlatformPageDefinitionService pageService =
            new PlatformPageDefinitionService(pageDao, moduleService, relationService);
    private final PlatformPresentationVariantService variantService =
            new PlatformPresentationVariantService(variantDao, pageService);
    private final PlatformPresentationRevisionService revisionService =
            new PlatformPresentationRevisionService(revisionDao, variantService);
    private final PlatformPresentationRevisionResolver revisionResolver =
            new PlatformPresentationRevisionResolver(variantService, revisionService);
    private final PlatformPresentationRevisionPublishService revisionPublishService =
            new PlatformPresentationRevisionPublishService(revisionService, variantService, pageService,
                    new PlatformPresentationTemplateCatalog());

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldRequirePageAliasToBeUniqueWithinModule() {
        String mainRelationId = seedMainRelation("crm.customer", "customer");

        try (TenantContext.Scope ignored = TenantContext.system("create global page definitions")) {
            pageService.insert(page("crm.customer", "management", mainRelationId));

            assertThatThrownBy(() -> pageService.insert(page("crm.customer", "management", mainRelationId)))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("unique");
        }
    }

    @Test
    void shouldKeepOnlyOneVariantForSamePageClientAndScope() {
        String pageId = seedPage();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            variantService.insert(variant(pageId, PlatformPresentationScopeType.TENANT, null));

            assertThatThrownBy(() -> variantService.insert(
                    variant(pageId, PlatformPresentationScopeType.TENANT, null)))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("Only one presentation variant");
        }
    }

    @Test
    void shouldRejectTenantVariantWithoutTenantScope() {
        String pageId = seedPage();

        try (TenantContext.Scope ignored = TenantContext.system("validate tenant presentation scope")) {
            assertThatThrownBy(() -> variantService.insert(
                    variant(pageId, PlatformPresentationScopeType.TENANT, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.presentation-variant.tenant-required"));
        }
    }

    @Test
    void shouldRequireTenantContextForOrganizationVariant() {
        String pageId = seedPage();
        PlatformPresentationVariant organizationVariant =
                variant(pageId, PlatformPresentationScopeType.ORGANIZATION, "organization-a");

        try (TenantContext.Scope ignored = TenantContext.system("validate organization presentation scope")) {
            assertThatThrownBy(() -> variantService.insert(organizationVariant))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.presentation-variant.organization-tenant-required"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String variantId = variantService.insert(organizationVariant);
            assertThat(variantService.select(variantId)).satisfies(saved -> {
                assertThat(saved.getTenantId()).isEqualTo("tenant-a");
                assertThat(saved.getOrganizationId()).isEqualTo("organization-a");
            });
        }
    }

    @Test
    void shouldRejectPublishedRevisionContentMutationWhileAllowingDraftRevision() {
        String pageId = seedPage();
        try (TenantContext.Scope ignored = TenantContext.system("create global presentation revision")) {
            String variantId = variantService.insert(variant(pageId, PlatformPresentationScopeType.GLOBAL, null));
            String publishedRevisionId;
            try (PlatformPresentationRevisionPublishContext.Scope publish =
                         PlatformPresentationRevisionPublishContext.open()) {
                publishedRevisionId = revisionService.insert(revision(
                        variantId, 1, PlatformPresentationRevisionStatus.PUBLISHED, "{\"kind\":\"list\"}"));
            }

            PlatformPresentationRevision changedPublished = revisionUpdate(revisionService.select(publishedRevisionId));
            changedPublished.setUiTreeJson("{\"kind\":\"card\"}");
            assertThatThrownBy(() -> revisionService.update(changedPublished))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.presentation-revision.published-content-mutation-denied"));

            String draftRevisionId = revisionService.insert(revision(
                    variantId, 2, PlatformPresentationRevisionStatus.DRAFT, "{\"kind\":\"card\"}"));
            assertThat(revisionService.select(draftRevisionId).getStatus())
                    .isEqualTo(PlatformPresentationRevisionStatus.DRAFT);
        }
    }

    @Test
    void shouldRejectDirectPublishedRevision() {
        String pageId = seedPage();
        try (TenantContext.Scope ignored = TenantContext.system("validate presentation revision publish guard")) {
            String variantId = variantService.insert(variant(pageId, PlatformPresentationScopeType.GLOBAL, null));
            assertThatThrownBy(() -> revisionService.insert(revision(
                    variantId, 1, PlatformPresentationRevisionStatus.PUBLISHED, "{\"kind\":\"list\"}")))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.presentation-revision.direct-publish-denied"));
        }
    }

    @Test
    void shouldRejectDirectPublishedRevisionStatusTransition() {
        String pageId = seedPage();
        try (TenantContext.Scope ignored = TenantContext.system("validate presentation revision status guard")) {
            String variantId = variantService.insert(variant(pageId, PlatformPresentationScopeType.GLOBAL, null));
            String publishedRevisionId;
            try (PlatformPresentationRevisionPublishContext.Scope publish =
                         PlatformPresentationRevisionPublishContext.open()) {
                publishedRevisionId = revisionService.insert(revision(
                        variantId, 1, PlatformPresentationRevisionStatus.PUBLISHED, "{\"kind\":\"list\"}"));
            }

            PlatformPresentationRevision draft = revisionUpdate(revisionService.select(publishedRevisionId));
            draft.setStatus(PlatformPresentationRevisionStatus.DRAFT);
            assertThatThrownBy(() -> revisionService.update(draft))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.presentation-revision.direct-status-transition-denied"));
        }
    }

    @Test
    void shouldResolveEnabledPublishedRevisionByOrganizationTenantThenGlobal() {
        String pageId = seedPage();
        String globalRevisionId;
        String tenantRevisionId;
        String organizationRevisionId;
        try (TenantContext.Scope ignored = TenantContext.system("seed global presentation revision")) {
            globalRevisionId = seedPublishedRevision(pageId, PlatformPresentationScopeType.GLOBAL, null, 1);
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantRevisionId = seedPublishedRevision(pageId, PlatformPresentationScopeType.TENANT, null, 1);
            organizationRevisionId = seedPublishedRevision(
                    pageId, PlatformPresentationScopeType.ORGANIZATION, "organization-a", 1);
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(revisionResolver.resolve(pageId, PlatformPresentationClientType.WEB,
                    "tenant-a", "organization-a"))
                    .map(PlatformPresentationRevision::getId)
                    .contains(organizationRevisionId);
            assertThat(revisionResolver.resolve(pageId, PlatformPresentationClientType.WEB,
                    "tenant-a", "organization-b"))
                    .map(PlatformPresentationRevision::getId)
                    .contains(tenantRevisionId);
            assertThat(revisionResolver.resolve(pageId, PlatformPresentationClientType.WEB,
                    "tenant-b", "organization-a"))
                    .map(PlatformPresentationRevision::getId)
                    .contains(globalRevisionId);
        }
    }

    @Test
    void shouldIgnoreDisabledPublishedRevisionAndReturnEmptyWithoutPublishedRevision() {
        String pageId = seedPage();
        String globalRevisionId;
        try (TenantContext.Scope ignored = TenantContext.system("seed global presentation revision")) {
            globalRevisionId = seedPublishedRevision(pageId, PlatformPresentationScopeType.GLOBAL, null, 1);
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String tenantVariantId = variantService.insert(variant(pageId, PlatformPresentationScopeType.TENANT, null));
            String tenantRevisionId;
            try (PlatformPresentationRevisionPublishContext.Scope publish =
                         PlatformPresentationRevisionPublishContext.open()) {
                tenantRevisionId = revisionService.insert(revision(tenantVariantId, 1,
                        PlatformPresentationRevisionStatus.PUBLISHED, "{\"kind\":\"list\"}"));
            }
            revisionService.disable(tenantRevisionId);

            assertThat(revisionResolver.resolve(pageId, PlatformPresentationClientType.WEB,
                    "tenant-a", null))
                    .map(PlatformPresentationRevision::getId)
                    .contains(globalRevisionId);
        }

        String pageWithoutPublishedRevision = seedPage("crm.order", "order");
        try (TenantContext.Scope ignored = TenantContext.system("seed draft presentation revision")) {
            String variantId = variantService.insert(
                    variant(pageWithoutPublishedRevision, PlatformPresentationScopeType.GLOBAL, null));
            revisionService.insert(revision(variantId, 1,
                    PlatformPresentationRevisionStatus.DRAFT, "{\"kind\":\"list\"}"));
        }
        assertThat(revisionResolver.resolve(pageWithoutPublishedRevision, PlatformPresentationClientType.WEB,
                null, null)).isEmpty();
    }

    @Test
    void shouldPublishValidatedRevisionAndArchiveThePreviousRevisionInTheSameVariant() {
        String pageId = seedPage();
        try (TenantContext.Scope ignored = TenantContext.system("publish presentation revision")) {
            String variantId = variantService.insert(variant(pageId, PlatformPresentationScopeType.GLOBAL, null));
            String firstRevisionId = revisionService.insert(revision(variantId, 1,
                    PlatformPresentationRevisionStatus.DRAFT, validManagementTree()));

            revisionPublishService.publish(firstRevisionId);
            assertThat(revisionService.select(firstRevisionId).getStatus())
                    .isEqualTo(PlatformPresentationRevisionStatus.PUBLISHED);

            String replacementRevisionId = revisionService.insert(revision(variantId, 2,
                    PlatformPresentationRevisionStatus.DRAFT, validManagementTree()));
            revisionPublishService.publish(replacementRevisionId);

            assertThat(revisionService.select(firstRevisionId).getStatus())
                    .isEqualTo(PlatformPresentationRevisionStatus.ARCHIVED);
            assertThat(revisionService.select(replacementRevisionId).getStatus())
                    .isEqualTo(PlatformPresentationRevisionStatus.PUBLISHED);
        }
    }

    private String seedPage() {
        return seedPage("crm.customer", "customer");
    }

    private String seedPage(String moduleAlias, String metadataAlias) {
        String mainRelationId = seedMainRelation(moduleAlias, metadataAlias);
        try (TenantContext.Scope ignored = TenantContext.system("create global page definition")) {
            return pageService.insert(page(moduleAlias, "management", mainRelationId));
        }
    }

    private String seedPublishedRevision(String pageId, PlatformPresentationScopeType scopeType,
                                         String organizationId, int revisionNo) {
        String variantId = variantService.insert(variant(pageId, scopeType, organizationId));
        try (PlatformPresentationRevisionPublishContext.Scope publish =
                     PlatformPresentationRevisionPublishContext.open()) {
            return revisionService.insert(revision(variantId, revisionNo,
                    PlatformPresentationRevisionStatus.PUBLISHED, "{\"kind\":\"list\"}"));
        }
    }

    private String seedMainRelation(String moduleAlias, String metadataAlias) {
        try (TenantContext.Scope ignored = TenantContext.system("seed page composition model")) {
            PlatformModule module = new PlatformModule();
            module.setAlias(moduleAlias);
            module.setApplicationAlias(moduleAlias.substring(0, moduleAlias.indexOf('.')));
            module.setTitle(moduleAlias);
            module.setParentId(TreeAbility.ROOT_ID);
            module.setModuleKind(ModuleKind.DYNAMIC);
            moduleService.insert(module);

            Metadata metadata = new Metadata();
            metadata.setApplicationAlias(module.getApplicationAlias());
            metadata.setAlias(metadataAlias);
            metadata.setTitle(metadataAlias);
            String metadataId = metadataService.insert(metadata);

            ModuleMetadataRelation relation = new ModuleMetadataRelation();
            relation.setModuleAlias(moduleAlias);
            relation.setMetadataId(metadataId);
            relation.setRelationAlias(metadataAlias);
            relation.setRelationRole(RelationRole.MAIN);
            return relationService.insert(relation);
        }
    }

    private PlatformPageDefinition page(String moduleAlias, String alias, String mainRelationId) {
        PlatformPageDefinition page = new PlatformPageDefinition();
        page.setModuleAlias(moduleAlias);
        page.setAlias(alias);
        page.setContractType(PlatformPageContractType.MANAGEMENT);
        page.setMainRelationId(mainRelationId);
        return page;
    }

    private PlatformPresentationVariant variant(String pageId,
                                                PlatformPresentationScopeType scopeType,
                                                String organizationId) {
        PlatformPresentationVariant variant = new PlatformPresentationVariant();
        variant.setPageId(pageId);
        variant.setClientType(PlatformPresentationClientType.WEB);
        variant.setScopeType(scopeType);
        variant.setOrganizationId(organizationId);
        return variant;
    }

    private PlatformPresentationRevision revision(String variantId, int revisionNo,
                                                  PlatformPresentationRevisionStatus status, String uiTreeJson) {
        PlatformPresentationRevision revision = new PlatformPresentationRevision();
        revision.setVariantId(variantId);
        revision.setRevisionNo(revisionNo);
        revision.setTemplateAlias("management");
        revision.setTemplateVersion(1);
        revision.setUiTreeJson(uiTreeJson);
        revision.setStatus(status);
        return revision;
    }

    private String validManagementTree() {
        return "{\"template\":\"management\",\"templateVersion\":1,\"nodes\":[]}";
    }

    private PlatformPresentationRevision revisionUpdate(PlatformPresentationRevision source) {
        PlatformPresentationRevision target = new PlatformPresentationRevision();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setVersion(source.getVersion());
        target.setVariantId(source.getVariantId());
        target.setRevisionNo(source.getRevisionNo());
        target.setTemplateAlias(source.getTemplateAlias());
        target.setTemplateVersion(source.getTemplateVersion());
        target.setUiTreeJson(source.getUiTreeJson());
        target.setStatus(source.getStatus());
        target.setTitle(source.getTitle());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        return target;
    }
}
