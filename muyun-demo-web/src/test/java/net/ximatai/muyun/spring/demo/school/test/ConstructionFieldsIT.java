package net.ximatai.muyun.spring.demo.school.test;

import net.ximatai.muyun.spring.boot.MuYunSpringApplication;
import net.ximatai.muyun.spring.platform.application.*;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.platform.ui.*;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@Testcontainers
@SpringBootTest(classes = MuYunSpringApplication.class, properties = "muyun.runtime.mode=development")
class ConstructionFieldsIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired ApplicationService applications;
    @Autowired PlatformModuleService modules;
    @Autowired ModuleMetadataRelationService moduleRelations;
    @Autowired ModuleMetadataOrchestrationService orchestration;
    @Autowired BusinessRuleGovernanceService businessRules;
    @Autowired ApplicationConstructionPlanService constructionPlans;
    @Autowired ApplicationConstructionInitializationService construction;
    @Autowired ApplicationConstructionFieldService constructionFields;
    @Autowired ApplicationConstructionDeliveryService delivery;
    @Autowired MetadataService metadataService;
    @Autowired MetadataModelChangeSetPreviewService metadataPreviews;
    @Autowired MetadataModelChangeSetApplyService metadataPublisher;
    @Autowired net.ximatai.muyun.spring.iam.tenant.TenantService tenants;
    @Autowired net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService dynamicRecords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired net.ximatai.muyun.spring.platform.web.PlatformModuleRuntimeContextService runtimeContexts;
    @Autowired net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService constructionPages;

    @Autowired net.ximatai.muyun.spring.platform.menu.MenuService constructionMenus;
    @Autowired net.ximatai.muyun.spring.platform.menu.MenuSchemeService constructionMenuSchemes;
    @Autowired PlatformPresentationVariantService presentationVariants;
    @Autowired PlatformPresentationRevisionService presentationRevisions;
    @Autowired PlatformPresentationRevisionPublishService presentationPublisher;

    @Autowired FieldSpecService fieldSpecs;
    @Autowired MetadataFieldService metadataFields;
    @Autowired MetadataModelDeletionService modelDeletion;
    @Autowired MetadataFieldReferenceConfigService referenceConfigs;

    @Test void standardGovernanceCanBeLinkedAndAcceptedWithoutInitializationReceipts() {
        String planId = UUID.randomUUID().toString().replace("-", "");
        String app = "linked" + planId.substring(0, 10);
        String moduleAlias = app + ".entry";
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员"));
             var scope = TenantContext.system("standard governance plan association")) {
            var application = new Application(); application.setAlias(app); application.setTitle("标准治理应用");
            applications.insert(application);
            var module = new PlatformModule(); module.setApplicationAlias(app); module.setAlias(moduleAlias);
            module.setTitle("登记"); module.setModuleKind(ModuleKind.DYNAMIC); modules.insert(module);
            var main = orchestration.createMainMetadata(moduleAlias,
                    new ModuleMainMetadataCreateCommand("entry", "登记", "public", "linked_" + planId, false));
            var content = new ApplicationConstructionPlanContent("登记", "使用现有标准模块", List.of("登记名称"), List.of(),
                    List.of(new ApplicationConstructionPlanContent.BusinessObject("entry", "登记", "名称录入", moduleAlias)),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of("录入并查看名称"),
                    List.of(new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "entry",
                            ApplicationConstructionRequirement.Mode.FIELD, "title", "名称字段", null)));
            var plan = constructionPlans.confirm(planId, new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 0, content));
            assertThat(plan.initializations()).isEmpty();
            assertThat(plan.constructionStatus()).isEqualTo("LINKED");
            assertThat(plan.moduleBindings()).containsExactly(new ApplicationConstructionPlanService.ModuleBinding("entry", moduleAlias));
            assertThat(construction.status(planId, "entry")).isNull();
            var description = constructionFields.describe(planId, "entry");
            assertThat(description.moduleAlias()).isEqualTo(moduleAlias);
            String spec = description.specs().stream().filter(value -> value.type().equals("STRING")).findFirst().orElseThrow().alias();
            var title = governedField("title", spec); title.field().setTitleField(true);
            applyGovernedFields(moduleAlias, main.relation().getId(), description.metadataVersion(), List.of(title));
            assertThat(delivery.task(planId).objects().getFirst().options())
                    .extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .contains(ApplicationConstructionDeliveryService.TaskAction.PUBLISH_PAGE)
                    .doesNotContain(ApplicationConstructionDeliveryService.TaskAction.INITIALIZE);
            publishStandardPage(planId, new PageLayout("entry", "登记", List.of("title"), List.of("title"), List.of("title")));
            var proposal = new ApplicationConstructionDeliveryService.Proposal(1, "entry", ApplicationConstructionDeliveryService.Kind.ENTRY,
                    "登记", List.of(), List.of(), List.of());
            delivery.confirm(planId, new ApplicationConstructionDeliveryService.Command(UUID.randomUUID().toString(), proposal, delivery.preview(planId, proposal).fingerprint()));
            assertThat(delivery.progress(planId, "entry").entryVisible()).isTrue();
            var acceptance = delivery.previewAcceptance(planId, "entry");
            delivery.confirmAcceptance(planId, new ApplicationConstructionDeliveryService.AcceptanceCommand(UUID.randomUUID().toString(), "entry", acceptance.fingerprint()));
            assertThat(constructionPlans.read(planId).constructionStatus()).isEqualTo("DELIVERED");
            assertThat(constructionPlans.read(planId).initializations()).isEmpty();
        }
    }

    @Test void selectionMappingsProtectBothFieldIdentitiesThroughRealDeletion() {
        String planId = UUID.randomUUID().toString().replace("-", "");
        String app = "affect" + planId.substring(0, 12);
        var content = new ApplicationConstructionPlanContent("回填依赖", "字段删除契约", List.of("选择商品"), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("product", "商品", "来源"),
                        new ApplicationConstructionPlanContent.BusinessObject("order", "订单", "目的"),
                        new ApplicationConstructionPlanContent.BusinessObject("other", "其他", "无关实体")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("删除依赖"),
                List.of(new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "product",
                        ApplicationConstructionRequirement.Mode.FIELD, "title", "商品名称", null)));
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员"));
             var system = TenantContext.system("reference dependency contract")) {
            createStandardPlan(planId, content, app, java.util.Map.of());
            var bindings = currentBindings(planId);
            var product = bindings.stream().filter(item -> item.objectKey().equals("product")).findFirst().orElseThrow();
            var order = bindings.stream().filter(item -> item.objectKey().equals("order")).findFirst().orElseThrow();
            var other = bindings.stream().filter(item -> item.objectKey().equals("other")).findFirst().orElseThrow();
            var specs = constructionFields.describe(planId, "order").specs();
            String text = specs.stream().filter(spec -> spec.type().equals("STRING") && spec.length() != null && spec.length() >= 32).findFirst().orElseThrow().alias();
            String decimal = specs.stream().filter(spec -> spec.type().equals("DECIMAL")).findFirst().orElseThrow().alias();
            for (var binding : bindings) {
                var title = governedField("title", text);
                title.field().setTitleField(true);
                applyGovernedFields(binding.moduleAlias(), binding.relationId(), metadataService.select(binding.metadataId()).getVersion(),
                        List.of(title, governedField("price", decimal)));
            }
            var reference = new MetadataFieldReferenceConfigDraft(product.moduleAlias(), product.metadataId(), "id", "title",
                    net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE,
                    net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY,
                    List.of(), false, List.of("price:price"));
            var incompatible = new MetadataFieldReferenceConfigDraft(product.moduleAlias(), product.metadataId(), "id", "title",
                    reference.cardinality(), reference.targetUnavailablePolicy(), List.of(), false, List.of("title:price"));
            var invalidCandidate = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                    order.relationId(), metadataService.select(order.metadataId()).getVersion(), java.util.Map.of(),
                    List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                            governedField("productId", text).field(),
                            new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null, incompatible, null))))), List.of(), List.of());
            assertThat(metadataPreviews.preview(order.moduleAlias(), invalidCandidate).errors())
                    .anySatisfy(error -> assertThat(error.message()).contains("类型不兼容"));
            applyGovernedFields(order.moduleAlias(), order.relationId(), metadataService.select(order.metadataId()).getVersion(),
                    List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                            governedField("productId", text).field(),
                            new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null, reference, null))));
            var compatibleSpec = new FieldSpec();
            compatibleSpec.setAlias("decimal_" + planId.substring(0, 12));
            compatibleSpec.setTitle("兼容小数");
            compatibleSpec.setFieldType(net.ximatai.muyun.spring.dynamic.metadata.FieldType.DECIMAL);
            compatibleSpec.setDefaultPrecision(18); compatibleSpec.setDefaultScale(2);
            fieldSpecs.insert(compatibleSpec);
            var compatibleField = metadataFields.list(net.ximatai.muyun.database.core.orm.Criteria.of()
                    .eq("metadataId", product.metadataId()).eq("fieldName", "price"), net.ximatai.muyun.spring.ability.PageRequests.all()).getFirst();
            compatibleField.setMetadataId(null); // Standard UPDATE drafts need not repeat persisted ownership.
            compatibleField.setFieldSpecAlias(compatibleSpec.getAlias());
            applyGovernedFields(product.moduleAlias(), product.relationId(), metadataService.select(product.metadataId()).getVersion(),
                    List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                            compatibleField.getId(), compatibleField.getVersion(), compatibleField)));
            assertThat(metadataFields.select(compatibleField.getId()).getFieldSpecAlias()).isEqualTo(compatibleSpec.getAlias());
            for (var binding : List.of(product, order)) {
                var field = metadataFields.list(net.ximatai.muyun.database.core.orm.Criteria.of()
                        .eq("metadataId", binding.metadataId()).eq("fieldName", "price"), net.ximatai.muyun.spring.ability.PageRequests.all()).getFirst();
                assertThatThrownBy(() -> modelDeletion.deleteField(binding.moduleAlias(), binding.relationId(), field.getId()))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                        .satisfies(error -> assertThat(((net.ximatai.muyun.spring.common.exception.PlatformException) error).details())
                                .containsEntry("referencedResource", "fieldReferenceAffect"));
                assertThat(metadataFields.select(field.getId())).isNotNull();
                field.setFieldSpecAlias(text);
                var changedType = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                        binding.relationId(), metadataService.select(binding.metadataId()).getVersion(), java.util.Map.of(),
                        List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.UPDATE,
                                field.getId(), field.getVersion(), field)))), List.of(), List.of());
                var typePreview = metadataPreviews.preview(binding.moduleAlias(), changedType);
                assertThat(typePreview.errors()).anySatisfy(error -> assertThat(error.message()).contains("类型不兼容"));
                assertThatThrownBy(() -> metadataPublisher.apply(binding.moduleAlias(),
                        new MetadataModelChangeSetApplyCommand(changedType, typePreview.proposalFingerprint())))
                        .isInstanceOf(RuntimeException.class);
                assertThat(metadataFields.select(field.getId()).getFieldSpecAlias())
                        .isEqualTo(binding == product ? compatibleSpec.getAlias() : decimal);
            }
            var unrelated = metadataFields.list(net.ximatai.muyun.database.core.orm.Criteria.of()
                    .eq("metadataId", other.metadataId()).eq("fieldName", "price"), net.ximatai.muyun.spring.ability.PageRequests.all()).getFirst();
            modelDeletion.deleteField(other.moduleAlias(), other.relationId(), unrelated.getId());
            assertThat(metadataFields.select(unrelated.getId())).isNull();
            var owner = metadataFields.list(net.ximatai.muyun.database.core.orm.Criteria.of()
                    .eq("metadataId", order.metadataId()).eq("fieldName", "productId"), net.ximatai.muyun.spring.ability.PageRequests.all()).getFirst();
            var config = referenceConfigs.findForRelation(owner.getId(), order.relationId());
            config.setAffectMappings(null);
            referenceConfigs.update(config);
            var released = metadataFields.list(net.ximatai.muyun.database.core.orm.Criteria.of()
                    .eq("metadataId", product.metadataId()).eq("fieldName", "price"), net.ximatai.muyun.spring.ability.PageRequests.all()).getFirst();
            modelDeletion.deleteField(product.moduleAlias(), product.relationId(), released.getId());
            assertThat(metadataFields.select(released.getId())).isNull();
        }
    }

    @Test void recognizesStandardGovernanceWithoutConstructionPublicationReceipts() {
        String planId = UUID.randomUUID().toString().replace("-", "");
        var content = new ApplicationConstructionPlanContent("订单", "登记订单", List.of("登记订单号"), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("entry", "订单", "登记")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("录入订单号"),
                List.of(new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0,
                        "entry", ApplicationConstructionRequirement.Mode.REQUIRED, "orderNumber", "订单号必填", null)));
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员"));
             var scope = TenantContext.system("standard governance construction test")) {
            createStandardPlan(planId, content, "manual" + planId.substring(0, 12), java.util.Map.of("entry", "registration_records"));
            assertThat(delivery.task(planId).objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action).contains(ApplicationConstructionDeliveryService.TaskAction.CONFIGURE_FIELDS);
            var binding = currentBindings(planId).getFirst();
            var description = constructionFields.describe(planId, "entry");
            var field = new MetadataField();
            field.setMetadataId(binding.metadataId()); field.setFieldName("orderNumber"); field.setColumnName("order_number");
            field.setTitle("订单号"); field.setRequired(true);
            field.setFieldSpecAlias(description.specs().stream().filter(spec -> spec.type().equals("STRING")).findFirst().orElseThrow().alias());
            var changeSet = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                    binding.relationId(), description.metadataVersion(), java.util.Map.of(),
                    List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null, field)))), List.of(), List.of());
            var preview = metadataPreviews.preview(binding.moduleAlias(), changeSet);
            metadataPublisher.apply(binding.moduleAlias(), new MetadataModelChangeSetApplyCommand(changeSet, preview.proposalFingerprint()));
            assertThat(constructionPlans.read(planId).fieldChanges()).isEmpty();
            assertThat(constructionFields.describe(planId, "entry").fields())
                    .anySatisfy(actual -> {
                        assertThat(actual.getFieldName()).isEqualTo("orderNumber");
                        assertThat(actual.getRequired()).isTrue();
                        assertThat(actual.getMetadataId()).isEqualTo(binding.metadataId());
                    });
            assertThat(delivery.task(planId).objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action).contains(ApplicationConstructionDeliveryService.TaskAction.PUBLISH_PAGE);
            var page = new PlatformPageDefinition();
            page.setModuleAlias(binding.moduleAlias()); page.setAlias("management"); page.setTitle("订单登记");
            page.setMainRelationId(binding.relationId()); page.setContractType(PlatformPageContractType.MANAGEMENT); page.setEnabled(true);
            var pageId = constructionPages.insert(page);
            var variant = new PlatformPresentationVariant(); variant.setPageId(pageId); variant.setTitle("订单登记");
            variant.setClientType(PlatformPresentationClientType.WEB); variant.setScopeType(PlatformPresentationScopeType.GLOBAL); variant.setEnabled(true);
            var variantId = presentationVariants.insert(variant);
            var revision = standardRevision(variantId, 1, "登记信息");
            var revisionId = presentationRevisions.insert(revision);
            assertThat(delivery.progress(planId, "entry").pagePublished()).isFalse();
            presentationPublisher.publish(revisionId);
            assertThat(delivery.progress(planId, "entry").pagePublished()).isTrue();
            assertThat(delivery.progress(planId, "entry").needsReview()).isFalse();
            assertThat(constructionPlans.read(planId).deliveries()).isEmpty();
            assertThat(delivery.task(planId).objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action).contains(ApplicationConstructionDeliveryService.TaskAction.CREATE_ENTRY);
            var entry = new ApplicationConstructionDeliveryService.Proposal(1, "entry", ApplicationConstructionDeliveryService.Kind.ENTRY,
                    "订单登记", List.of(), List.of(), List.of());
            var manualMenu = new net.ximatai.muyun.spring.platform.menu.Menu();
            manualMenu.setSchemeId(constructionMenuSchemes.resolveCurrentUserScheme(CurrentUserContext.currentUser().orElseThrow()).getId());
            manualMenu.setParentId(net.ximatai.muyun.spring.ability.TreeAbility.ROOT_ID); manualMenu.setTitle("人工配置入口");
            manualMenu.setModuleAlias(binding.moduleAlias()); manualMenu.setEnabled(true);
            manualMenu.setOpenMode(net.ximatai.muyun.spring.platform.menu.MenuOpenMode.TAB);
            manualMenu.setPageMode(net.ximatai.muyun.spring.platform.menu.MenuPageMode.LIST);
            String manualMenuId = constructionMenus.insert(manualMenu);
            assertThat(delivery.progress(planId, "entry").entryVisible()).isTrue();
            assertThat(constructionPlans.read(planId).deliveries()).isEmpty();
            assertThatThrownBy(() -> delivery.preview(planId, entry)).hasMessageContaining("已有访问入口");
            var disabledMenu = constructionMenus.select(manualMenuId); disabledMenu.setEnabled(false); constructionMenus.update(disabledMenu);
            assertThat(delivery.progress(planId, "entry").entryVisible()).isFalse();
            delivery.confirm(planId, new ApplicationConstructionDeliveryService.Command(UUID.randomUUID().toString(), entry, delivery.preview(planId, entry).fingerprint()));
            assertThat(delivery.task(planId).objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action).contains(ApplicationConstructionDeliveryService.TaskAction.VERIFY_BUSINESS);
            var acceptance = delivery.previewAcceptance(planId, "entry");
            var nextRevisionId = presentationRevisions.insert(standardRevision(variantId, 2, "订单资料"));
            presentationPublisher.publish(nextRevisionId);
            assertThat(delivery.progress(planId, "entry").pagePublished()).isTrue();
            assertThat(delivery.progress(planId, "entry").needsReview()).isFalse();
            assertThatThrownBy(() -> delivery.confirmAcceptance(planId, new ApplicationConstructionDeliveryService.AcceptanceCommand(
                    UUID.randomUUID().toString(), "entry", acceptance.fingerprint()))).hasMessageContaining("验收基线已变化");
            var disabledPage = constructionPages.select(pageId); disabledPage.setEnabled(false); constructionPages.update(disabledPage);
            assertThat(delivery.progress(planId, "entry").pagePublished()).isFalse();
            disabledPage.setEnabled(true); constructionPages.update(disabledPage);
            var currentAcceptance = delivery.previewAcceptance(planId, "entry");
            delivery.confirmAcceptance(planId, new ApplicationConstructionDeliveryService.AcceptanceCommand(UUID.randomUUID().toString(), "entry", currentAcceptance.fingerprint()));
            assertThat(delivery.task(planId).objects().getFirst().complete()).isTrue();
            assertThat(constructionPlans.read(planId).fieldChanges()).isEmpty();
        }
    }

    @Test void standardFieldPublicationFlowsIntoPageEntryAndBusinessAcceptance() throws Exception {
        String planId = UUID.randomUUID().toString().replace("-", "");
        String app = "field" + planId.substring(0, 12);
        var content = new ApplicationConstructionPlanContent("订单", "登记订单", List.of("录入", "记录备注"), List.of("审批"),
            List.of(new ApplicationConstructionPlanContent.BusinessObject("entry", "订单", "登记")),
            List.of(), List.of("订单号必填且不重复"), List.of(), List.of(), List.of(), List.of("录入并查询"), List.of(new net.ximatai.muyun.spring.platform.application.ApplicationConstructionRequirement(
                net.ximatai.muyun.spring.platform.application.ApplicationConstructionRequirement.Section.SCOPE, 0, "entry",
                net.ximatai.muyun.spring.platform.application.ApplicationConstructionRequirement.Mode.MANUAL, "", "实际录入并查询一笔订单", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0, "entry", ApplicationConstructionRequirement.Mode.REQUIRED, "orderNumber", "订单号由系统要求填写", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0, "entry", ApplicationConstructionRequirement.Mode.UNIQUE, "orderNumber", "系统拒绝重复订单号", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 1, "entry", ApplicationConstructionRequirement.Mode.FIELD, "remark", "可选填备注", null)));
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员")); var scope = TenantContext.system("field acceptance")) {
            createStandardPlan(planId, content, app, java.util.Map.of("entry", "registration_records"));
            MockMvc mvc = webAppContextSetup(webApplicationContext).build();
            var description = constructionFields.describe(planId, "entry");
            assertThat(delivery.task(planId).objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action).contains(ApplicationConstructionDeliveryService.TaskAction.CONFIGURE_FIELDS);
            assertThat(delivery.progress(planId, "entry").remainingWork()).anyMatch(value -> value.contains("未兑现"));
            String spec = description.specs().stream().filter(value -> value.type().equals("STRING")).findFirst().orElseThrow().alias();
            var binding = currentBindings(planId).getFirst();
            var fieldDrafts = List.of(new FieldDraft("orderNumber", "订单号", spec, true, true, true, null, false),
                    new FieldDraft("remark", "备注", spec, false, false, false, null, false));
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                publishFields(planId, "entry", fieldDrafts); status.setRollbackOnly();
            });
            assertThat(constructionFields.describe(planId, "entry").fields()).noneMatch(field -> field.getFieldName().equals("orderNumber"));
            publishFields(planId, "entry", fieldDrafts);
            for (String suffix : List.of("/field-changes", "/field-changes/preview", "/initializations")) {
                var response = mvc.perform(post("/platform.application-construction-plans/" + planId + suffix)
                        .contentType("application/json").content("{}")).andReturn().getResponse();
                assertThat(response.getStatus()).as("retired construction writes").isIn(404, 405);
            }
            assertThat(constructionPlans.read(planId).fieldChanges()).isEmpty();
            assertThat(delivery.task(planId).objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .contains(ApplicationConstructionDeliveryService.TaskAction.PUBLISH_PAGE);
            assertThat(metadataPreviews.preview(binding.moduleAlias(), fieldChanges(planId, "entry", fieldDrafts)).errors()).isNotEmpty();
            var pageProposal = new PageLayout("entry",
                    "订单登记", List.of("orderNumber"), List.of("orderNumber"), List.of("orderNumber"));
            publishStandardPage(planId, pageProposal);
            assertThat(delivery.progress(planId, "entry").needsReview()).isTrue();
            var completePageProposal = new PageLayout("entry",
                    "订单登记", List.of("orderNumber"), List.of("orderNumber", "remark"), List.of("orderNumber"));
            var pageReceipt = publishStandardPage(planId, completePageProposal);
            assertThat(constructionPlans.read(planId).deliveries()).isEmpty();
            assertThat(delivery.progress(planId, "entry").pagePublished()).isTrue();
            assertThat(delivery.task(planId).objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action).contains(ApplicationConstructionDeliveryService.TaskAction.CREATE_ENTRY);
            var installedContext = runtimeContexts.context(binding.moduleAlias());
            var installedPage = installedContext.uiDescriptor();
            assertThat(installedPage.page().detail().editor()).isNotNull();
            assertThat(installedContext.actions()).anyMatch(action -> "create".equals(action.actionCode()));
            assertThat(!installedPage.page().managedActions() || installedPage.page().actions().stream().anyMatch(action -> "create".equals(action.actionCode())))
                    .as("生成页面必须保留标准新建入口，不能以空动作列表隐藏它").isTrue();

            var entryProposal = new ApplicationConstructionDeliveryService.Proposal(1, "entry", ApplicationConstructionDeliveryService.Kind.ENTRY,
                    "订单登记", List.of(), List.of(), List.of());
            var entryPreview = delivery.preview(planId, entryProposal);
            var entryCommand = new ApplicationConstructionDeliveryService.Command(UUID.randomUUID().toString(), entryProposal, entryPreview.fingerprint());
            var entryReceipt = delivery.confirm(planId, entryCommand);
            assertThat(delivery.confirm(planId, entryCommand)).isEqualTo(entryReceipt);
            assertThat(delivery.progress(planId, "entry").entryVisible()).isTrue();
            assertThat(delivery.task(planId).objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action).contains(ApplicationConstructionDeliveryService.TaskAction.VERIFY_BUSINESS);
            assertThat(delivery.progress(planId, "entry").needsReview()).isFalse();
            assertThatThrownBy(() -> delivery.preview(planId, entryProposal)).hasMessageContaining("已有访问入口");
            var businessTenant = new net.ximatai.muyun.spring.iam.tenant.Tenant();
            businessTenant.setTitle("建设验收租户"); businessTenant.setAlias("accept_" + planId.substring(0, 10));
            String tenantId = tenants.insert(businessTenant);
            try (var businessScope = TenantContext.use(tenantId)) {
            String module = binding.moduleAlias();
            var created = mvc.perform(post("/" + module + "/insert").contentType("application/json")
                    .content("{\"values\":{\"orderNumber\":\"ACCEPT-001\"}}" )).andReturn().getResponse();
            assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
            var queried = mvc.perform(post("/" + module + "/query").contentType("application/json")
                    .content("{\"quickSearch\":\"ACCEPT-001\",\"quickSearchFields\":[\"orderNumber\"]}")).andReturn().getResponse();
            assertThat(queried.getStatus()).as(queried.getContentAsString()).isEqualTo(200);
            assertThat(queried.getContentAsString()).contains("ACCEPT-001");
            var savedRecord = dynamicRecords.mainEntity(module).list(net.ximatai.muyun.database.core.orm.Criteria.of().eq("orderNumber", "ACCEPT-001"), net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)).getFirst();
            var detail = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/" + module + "/view/" + savedRecord.getId())).andReturn().getResponse();
            assertThat(detail.getStatus()).as(detail.getContentAsString()).isEqualTo(200);
            assertThat(detail.getContentAsString()).contains("ACCEPT-001");
            var duplicate = mvc.perform(post("/" + module + "/insert").contentType("application/json")
                    .content("{\"values\":{\"orderNumber\":\"ACCEPT-001\"}}" )).andReturn().getResponse();
            assertThat(duplicate.getStatus()).isGreaterThanOrEqualTo(400);
            assertThat(duplicate.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("订单号不能重复");
            assertThat(mvc.perform(post("/" + module + "/insert").contentType("application/json")
                    .content("{\"values\":{}}" )).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
            }
            assertThat(delivery.progress(planId, "entry").acceptanceConfirmed()).isFalse();
            var acceptancePreview = delivery.previewAcceptance(planId, "entry");
            var acceptanceCommand = new ApplicationConstructionDeliveryService.AcceptanceCommand(UUID.randomUUID().toString(), "entry", acceptancePreview.fingerprint());
            var acceptanceReceipt = delivery.confirmAcceptance(planId, acceptanceCommand);
            assertThat(delivery.confirmAcceptance(planId, acceptanceCommand)).isEqualTo(acceptanceReceipt);
            assertThat(delivery.progress(planId, "entry").acceptanceConfirmed()).isTrue();
            assertThat(delivery.task(planId).objects().getFirst().complete()).isTrue();
            try (var other = CurrentUserContext.use(CurrentUser.systemUser("other-governor", "其他配置人员"))) {
                var changedPage = constructionPages.select(pageReceipt.pageId());
                changedPage.setTitle("调整后的订单登记");
                constructionPages.update(changedPage);
                assertThat(constructionPages.select(pageReceipt.pageId()).getTitle()).isEqualTo("调整后的订单登记");
            }
            // Current governance can change independently of the original author's historical design.
            assertThat(delivery.progress(planId, "entry").acceptanceConfirmed()).isFalse();
            assertThat(constructionPlans.read(planId).constructionStatus()).isEqualTo("DELIVERED");
            assertThat(delivery.task(planId).objects().getFirst().options())
                    .extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.REVIEW_CURRENT_CONFIGURATION);
            assertThatThrownBy(() -> constructionPlans.confirm(planId, new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 1,
                new ApplicationConstructionPlanContent("修订订单", content.goal(), content.inScope(), content.outOfScope(), content.objects(), content.relationships(), content.rules(), content.questions(), content.assumptions(), content.decisions(), content.acceptanceExamples(), content.requirements()))))
                    .hasMessageContaining("已交付");
            assertThatThrownBy(() -> delivery.previewAcceptance(planId, "entry")).hasMessageContaining("已交付");
            try (var other = CurrentUserContext.use(CurrentUser.systemUser("other-owner", "其他用户"))) {
                assertThatThrownBy(() -> constructionFields.describe(planId, "entry")).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
        }
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("tenant-user", "用户", "tenant"))) {
            assertThatThrownBy(() -> constructionFields.describe(planId, "entry")).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        }
    }
    @Test void buildsReferencesAcrossNewObjectsAndReusesExistingModulesWithoutAdoptingThem() throws Exception {
        String planId = UUID.randomUUID().toString().replace("-", "");
        String app = "linked" + planId.substring(0, 10);
        var objects = List.of(new ApplicationConstructionPlanContent.BusinessObject("party", "往来单位", "登记名称"),
                new ApplicationConstructionPlanContent.BusinessObject("agreement", "协议", "选择往来单位"));
        var content = new ApplicationConstructionPlanContent("协议登记", "关联登记", List.of("登记单位与协议"), List.of(), objects,
                List.of("协议选择已登记单位"), List.of(), List.of(), List.of(), List.of(), List.of("录入单位后选择它登记协议"),
                List.of(new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "party", ApplicationConstructionRequirement.Mode.MANUAL, "", "实际登记单位核验", null),
                        new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "agreement", ApplicationConstructionRequirement.Mode.MANUAL, "", "实际登记协议核验", null),
                        new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RELATION, 0, "agreement",
                        ApplicationConstructionRequirement.Mode.REFERENCE, "partyId", "从单位中选择一条，不重复抄写名称",
                        new ApplicationConstructionRequirement.Reference("party", ""))));
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员"));
             var system = TenantContext.system("reference construction acceptance")) {
            createStandardPlan(planId, content, app, java.util.Map.of());
            var targetBefore = constructionFields.describe(planId, "party");
            String spec = targetBefore.specs().stream().filter(value -> value.type().equals("STRING") && value.length() != null && value.length() >= 32)
                    .findFirst().orElseThrow().alias();
            // A record name can enable references without an assistant-specific FIELD binding.
            // Its structure is still checked by the standard metadata publisher.
            var invalidName = metadataPreviews.preview(app + ".party", fieldChanges(planId, "party",
                    List.of(new FieldDraft("partyName", "单位名称", spec, true, false, false, null, true))));
            assertThat(invalidName.errors()).extracting(MetadataChangeSetValidationIssue::message)
                    .anyMatch(message -> message.contains("标准 title"));
            assertThat(constructionFields.describe(planId, "party").metadataVersion()).isEqualTo(targetBefore.metadataVersion());
            publishFields(planId, "party", List.of(new FieldDraft("title", "单位名称", spec, true, false, false, null, true)));
            var targetPage = new PageLayout("party",
                    "单位资料", List.of("title"), List.of("title"), List.of("title"));
            publishStandardPage(planId, targetPage);
            String targetAlias = app + ".party";
            assertThat(constructionFields.businessObjects()).extracting(ReferenceTargetFieldCatalogService.ModuleCandidate::alias).contains(targetAlias);
            var target = constructionFields.referenceTarget(targetAlias);
            assertThat(target.labelFields()).extracting(ReferenceTargetFieldCandidate::fieldName).contains("title");
            var reference = new MetadataFieldReferenceConfigDraft(targetAlias, target.targetMetadataId(), "id", "title",
                    net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE,
                    net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY, List.of(), false);
            var field = new FieldDraft("partyId", "往来单位", spec, false, false, false, reference, false);
            publishFields(planId, "agreement", List.of(field, new FieldDraft("subject", "主题", spec, false, false, false, null, false)));
            assertThat(constructionFields.describe(planId, "agreement").references().get("partyId").targetModuleAlias()).isEqualTo(targetAlias);
            assertThat(delivery.task(planId).objects().stream().filter(item -> item.objectKey().equals("agreement")).findFirst().orElseThrow().requirements())
                    .filteredOn(item -> item.section() == ApplicationConstructionRequirement.Section.RELATION)
                    .allSatisfy(item -> assertThat(item.status()).isEqualTo(ApplicationConstructionRequirements.Status.CONFIGURATION_MATCHED));
            var page = new PageLayout("agreement",
                    "协议登记", List.of("partyId"), List.of("partyId"), List.of());
            var missingFieldPage = new PageLayout("agreement",
                    "协议登记", List.of("partyId"), List.of("subject"), List.of());
            publishStandardPage(planId, missingFieldPage);
            assertThat(delivery.progress(planId, "agreement").needsReview()).isTrue();
            publishStandardPage(planId, page);

            // A second, unrelated business reuses the same public target without adopting its configuration.
            String reusePlan = UUID.randomUUID().toString().replace("-", "");
            var reuseContent = new ApplicationConstructionPlanContent("走访登记", "记录走访单位", List.of("登记走访"), List.of(),
                    List.of(new ApplicationConstructionPlanContent.BusinessObject("visit", "走访", "登记")), List.of("选择已有单位"),
                    List.of(), List.of(), List.of(), List.of(), List.of("选择单位登记走访"),
                    List.of(new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "visit", ApplicationConstructionRequirement.Mode.MANUAL, "", "实际录入走访核验", null),
                            new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RELATION, 0, "visit",
                            ApplicationConstructionRequirement.Mode.REFERENCE, "partyId", "复用已有单位资料",
                            new ApplicationConstructionRequirement.Reference("", targetAlias))));
            createStandardPlan(reusePlan, reuseContent, app, java.util.Map.of());
            int targetVersion = constructionFields.describe(planId, "party").metadataVersion();
            publishFields(reusePlan, "visit", List.of(field));
            assertThat(constructionPlans.read(reusePlan).initializations()).isEmpty();
            assertThat(constructionPlans.read(reusePlan).moduleBindings()).hasSize(1);
            assertThat(constructionFields.describe(planId, "party").metadataVersion()).isEqualTo(targetVersion);
            assertThat(constructionFields.describe(reusePlan, "visit").references().get("partyId").targetModuleAlias()).isEqualTo(targetAlias);

            var tenant = new net.ximatai.muyun.spring.iam.tenant.Tenant();
            tenant.setTitle("关联验收"); tenant.setAlias("linked_" + planId.substring(0, 10));
            String tenantId = tenants.insert(tenant);
            var mvc = webAppContextSetup(webApplicationContext).build();
            try (var business = TenantContext.use(tenantId)) {
                var created = mvc.perform(post("/" + targetAlias + "/insert").contentType("application/json")
                        .content("{\"values\":{\"title\":\"晨光公司\"}}" )).andReturn().getResponse();
                assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
                var party = dynamicRecords.mainEntity(targetAlias).list(net.ximatai.muyun.database.core.orm.Criteria.of().eq("title", "晨光公司"),
                        net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)).getFirst();
                var saved = mvc.perform(post("/" + app + ".agreement/insert").contentType("application/json")
                        .content("{\"values\":{\"partyId\":\"" + party.getId() + "\"}}" )).andReturn().getResponse();
                assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(201);
                assertThat(mvc.perform(post("/" + app + ".agreement/insert").contentType("application/json")
                        .content("{\"values\":{\"partyId\":\"missing-record\"}}" )).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
            }
        }
    }

    @Test void childAndCalculationEvidenceUsesCurrentGovernanceAndPagePlacement() {
        String planId = UUID.randomUUID().toString().replace("-", "");
        var content = new ApplicationConstructionPlanContent("订货", "一单多项并计算金额", List.of("记录单号和数量"), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("entry", "订货单", "登记")),
                List.of("一单包含多条明细"), List.of("小计与总额自动计算"), List.of(), List.of(), List.of(),
                List.of("两行明细保存后重开，修改和删除明细重新核算"), List.of(
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "entry", ApplicationConstructionRequirement.Mode.FIELD, "number", "记录单号", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "entry", ApplicationConstructionRequirement.Mode.REQUIRED, "lines.quantity", "逐行填写数量", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RELATION, 0, "entry", ApplicationConstructionRequirement.Mode.CHILD, "lines", "明细属于该单据", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0, "entry", ApplicationConstructionRequirement.Mode.CALCULATION, "lines.amount", "数量乘成交价，需样例试算", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0, "entry", ApplicationConstructionRequirement.Mode.CALCULATION, "total", "汇总明细，需样例试算", null)));
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员"));
             var scope = TenantContext.system("child construction contract")) {
            createStandardPlan(planId, content, "child" + planId.substring(0, 12), java.util.Map.of("entry", "orders"));
            var binding = currentBindings(planId).getFirst();
            var description = constructionFields.describe(planId, "entry");
            String text = description.specs().stream().filter(spec -> spec.type().equals("STRING")).findFirst().orElseThrow().alias();
            String decimal = description.specs().stream().filter(spec -> spec.type().equals("DECIMAL")).findFirst().orElseThrow().alias();
            publishFields(planId, "entry", List.of(new FieldDraft("number", "单号", text, true, false, false, null, false),
                    new FieldDraft("total", "合计", decimal, false, false, false, null, false),
                    new FieldDraft("lines", "同名备注", text, false, false, false, null, false)));
            var child = orchestration.createChildMetadata(binding.moduleAlias(), binding.relationId(),
                    new ModuleChildMetadataCreateCommand("lines", "订货明细", "public", "child_" + planId));
            var childDrafts = List.of("quantity", "price", "amount").stream().map(name -> {
                var field = new MetadataField(); field.setFieldName(name); field.setColumnName(name); field.setTitle(name);
                field.setFieldSpecAlias(decimal); field.setRequired(name.equals("quantity"));
                return new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null, field);
            }).toList();
            var childChange = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                    child.relation().getId(), child.metadata().getVersion(), java.util.Map.of(), childDrafts)), List.of(), List.of());
            var checked = metadataPreviews.preview(binding.moduleAlias(), childChange);
            assertThat(checked.errors()).isEmpty();
            metadataPublisher.apply(binding.moduleAlias(), new MetadataModelChangeSetApplyCommand(childChange, checked.proposalFingerprint()));
            var status = delivery.progress(planId, "entry");
            assertThat(status.requirements()).filteredOn(item -> item.fieldName().equals("lines")).allSatisfy(item ->
                    assertThat(item.status()).isEqualTo(ApplicationConstructionRequirements.Status.CONFIGURATION_MATCHED));
            assertThat(status.requirements()).filteredOn(item -> item.section() == ApplicationConstructionRequirement.Section.RULE)
                    .allSatisfy(item -> assertThat(item.status()).isEqualTo(ApplicationConstructionRequirements.Status.CONFIGURATION_MISSING));
            var page = new PageLayout("entry",
                    "订货单", List.of("number", "total"), List.of("number", "total"), List.of(),
                    java.util.Map.of("lines", List.of("quantity", "price", "amount")));
            assertThat(delivery.progress(planId, "entry").remainingWork()).anyMatch(value -> value.contains("未兑现"));
            var rules = List.of(new BusinessRuleProposal("lineAmount", net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION,
                            "lines.amount", "{lines.quantity} * {lines.price}", true, null),
                    new BusinessRuleProposal("total", net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION, "total", "SUM({lines.amount})", true, null));
            var rulePreview = businessRules.preview(binding.moduleAlias(), new BusinessRulePreviewCommand(rules));
            assertThat(rulePreview.errors()).isEmpty();
            businessRules.apply(binding.moduleAlias(), new BusinessRuleApplyCommand(rules, rulePreview.snapshot().baselineFingerprint(), rulePreview.proposalFingerprint()));
            var missingChild = new PageLayout("entry",
                    "订货单", List.of("number", "total"), List.of("number", "total"), List.of());
            publishStandardPage(planId, missingChild);
            assertThat(delivery.progress(planId, "entry").needsReview()).isTrue();
            var published = publishStandardPage(planId, page);
            assertThat(delivery.progress(planId, "entry").needsReview()).isFalse();
            assertThat(delivery.progress(planId, "entry").requirements()).allSatisfy(item ->
                    assertThat(item.status()).isEqualTo(ApplicationConstructionRequirements.Status.CONFIGURATION_MATCHED));
            var entry = new ApplicationConstructionDeliveryService.Proposal(1, "entry", ApplicationConstructionDeliveryService.Kind.ENTRY,
                    "订货单", List.of(), List.of(), List.of());
            delivery.confirm(planId, new ApplicationConstructionDeliveryService.Command(UUID.randomUUID().toString(), entry, delivery.preview(planId, entry).fingerprint()));
            var acceptance = delivery.previewAcceptance(planId, "entry");
            // Changing the formula without changing its target must invalidate the previous approval baseline.
            var revisedRules = List.of(rules.getFirst(), new BusinessRuleProposal("total", net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION,
                    "total", "SUM({lines.amount}) + 1", true, null));
            var revised = businessRules.preview(binding.moduleAlias(), new BusinessRulePreviewCommand(revisedRules));
            businessRules.apply(binding.moduleAlias(), new BusinessRuleApplyCommand(revisedRules, revised.snapshot().baselineFingerprint(), revised.proposalFingerprint()));
            assertThatThrownBy(() -> delivery.confirmAcceptance(planId, new ApplicationConstructionDeliveryService.AcceptanceCommand(
                    UUID.randomUUID().toString(), "entry", acceptance.fingerprint()))).hasMessageContaining("验收基线已变化");
            var noCalculations = businessRules.preview(binding.moduleAlias(), new BusinessRulePreviewCommand(List.of()));
            businessRules.apply(binding.moduleAlias(), new BusinessRuleApplyCommand(List.of(), noCalculations.snapshot().baselineFingerprint(), noCalculations.proposalFingerprint()));
            assertThatThrownBy(() -> delivery.previewAcceptance(planId, "entry")).hasMessageContaining("配置证据");
            assertThat(published.pageId()).isNotBlank();
        }
    }

    @Test void standardGovernanceDeliversAnOrderWhoseTenantBusinessSaveRecalculatesDetails() throws Exception {
        String planId = UUID.randomUUID().toString().replace("-", "");
        String app = "sales" + planId.substring(0, 12);
        var content = new ApplicationConstructionPlanContent("订单登记", "选择客户并登记订单明细", List.of("登记订单"), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("customer", "客户", "客户资料"),
                        new ApplicationConstructionPlanContent.BusinessObject("product", "商品", "当前售价"),
                        new ApplicationConstructionPlanContent.BusinessObject("order", "订单", "订单登记")),
                List.of("引用已有客户", "订单包含明细"), List.of("逐行计算小计并汇总总额"), List.of(), List.of(), List.of(),
                List.of("保存、重开并修改明细"), List.of(
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "customer",
                        ApplicationConstructionRequirement.Mode.FIELD, "title", "客户名称", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0, "order",
                        ApplicationConstructionRequirement.Mode.FIELD, "number", "订单号", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RELATION, 0, "order",
                        ApplicationConstructionRequirement.Mode.REFERENCE, "customerId", "选择已有客户",
                        new ApplicationConstructionRequirement.Reference("customer", "")),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RELATION, 1, "order",
                        ApplicationConstructionRequirement.Mode.CHILD, "lines", "订单明细", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0, "order",
                        ApplicationConstructionRequirement.Mode.CALCULATION, "lines.amount", "明细小计", null),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0, "order",
                        ApplicationConstructionRequirement.Mode.CALCULATION, "total", "订单合计", null)));
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员"));
             var system = TenantContext.system("order delivery contract")) {
            createStandardPlan(planId, content, app, java.util.Map.of());
            var bindings = currentBindings(planId);
            var customer = bindings.stream().filter(item -> item.objectKey().equals("customer")).findFirst().orElseThrow();
            var product = bindings.stream().filter(item -> item.objectKey().equals("product")).findFirst().orElseThrow();
            var order = bindings.stream().filter(item -> item.objectKey().equals("order")).findFirst().orElseThrow();
            var description = constructionFields.describe(planId, "order");
            String text = description.specs().stream().filter(spec -> spec.type().equals("STRING") && spec.length() != null && spec.length() >= 32)
                    .findFirst().orElseThrow().alias();
            String decimal = description.specs().stream().filter(spec -> spec.type().equals("DECIMAL")).findFirst().orElseThrow().alias();
            var customerTitle = governedField("title", text);
            customerTitle.field().setRequired(true); customerTitle.field().setTitleField(true);
            applyGovernedFields(customer.moduleAlias(), customer.relationId(), metadataService.select(customer.metadataId()).getVersion(), List.of(customerTitle));
            var customerPage = new PageLayout("customer",
                    "客户资料", List.of("title"), List.of("title"), List.of());
            publishStandardPage(planId, customerPage);
            var productTitle = governedField("title", text);
            productTitle.field().setRequired(true); productTitle.field().setTitleField(true);
            applyGovernedFields(product.moduleAlias(), product.relationId(), metadataService.select(product.metadataId()).getVersion(),
                    List.of(productTitle, governedField("price", decimal)));
            publishStandardPage(planId, new PageLayout("product", "商品", List.of("title", "price"), List.of("title", "price"), List.of()));
            var customerReference = new MetadataFieldReferenceConfigDraft(customer.moduleAlias(), customer.metadataId(), "id", "title",
                    net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE,
                    net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY, List.of(), false);
            var referenceField = governedField("customerId", text).field();
            applyGovernedFields(order.moduleAlias(), order.relationId(), description.metadataVersion(), List.of(
                    governedField("number", text), governedField("total", decimal),
                    new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null, referenceField,
                            new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null, customerReference, null))));
            var child = orchestration.createChildMetadata(order.moduleAlias(), order.relationId(),
                    new ModuleChildMetadataCreateCommand("lines", "订单明细", "public", "order_lines_" + planId));
            applyGovernedFields(order.moduleAlias(), child.relation().getId(), child.metadata().getVersion(),
                    List.of(governedField("quantity", decimal), governedField("price", decimal), governedField("amount", decimal)));
            var productReference = new MetadataFieldReferenceConfigDraft(product.moduleAlias(), product.metadataId(), "id", "title",
                    net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE,
                    net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY,
                    List.of(), false, List.of("price:price"));
            var productCandidate = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                    child.relation().getId(), metadataService.select(child.metadata().getId()).getVersion(), java.util.Map.of(),
                    List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                            governedField("productId", text).field(),
                            new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null, productReference, null))))), List.of(), List.of());
            var productPreview = metadataPreviews.preview(order.moduleAlias(), productCandidate);
            assertThat(productPreview.errors()).isEmpty();
            var revisedReference = new MetadataFieldReferenceConfigDraft(product.moduleAlias(), product.metadataId(), "id", "title",
                    productReference.cardinality(), productReference.targetUnavailablePolicy(), List.of(), false, List.of("price:amount"));
            var revisedCandidate = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                    child.relation().getId(), metadataService.select(child.metadata().getId()).getVersion(), java.util.Map.of(),
                    List.of(new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null,
                            governedField("productId", text).field(),
                            new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null, revisedReference, null))))), List.of(), List.of());
            var revisedPreview = metadataPreviews.preview(order.moduleAlias(), revisedCandidate);
            assertThat(revisedPreview.errors()).isEmpty();
            assertThat(revisedPreview.proposalFingerprint()).isNotEqualTo(productPreview.proposalFingerprint());
            assertThatThrownBy(() -> metadataPublisher.apply(order.moduleAlias(),
                    new MetadataModelChangeSetApplyCommand(revisedCandidate, productPreview.proposalFingerprint())))
                    .hasMessageContaining("fingerprint is stale");
            metadataPublisher.apply(order.moduleAlias(),
                    new MetadataModelChangeSetApplyCommand(productCandidate, productPreview.proposalFingerprint()));
            var rules = List.of(new BusinessRuleProposal("lineAmount", net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION,
                            "lines.amount", "{lines.quantity} * {lines.price}", true, null),
                    new BusinessRuleProposal("total", net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION,
                            "total", "SUM({lines.amount})", true, null));
            var checked = businessRules.preview(order.moduleAlias(), new BusinessRulePreviewCommand(rules));
            assertThat(checked.errors()).isEmpty();
            businessRules.apply(order.moduleAlias(), new BusinessRuleApplyCommand(rules, checked.snapshot().baselineFingerprint(), checked.proposalFingerprint()));
            var page = new PageLayout("order",
                    "订单登记", List.of("number", "customerId", "total"), List.of("number", "customerId", "total"), List.of(),
                    java.util.Map.of("lines", List.of("productId", "quantity", "price", "amount")));
            publishStandardPage(planId, page);
            var entry = new ApplicationConstructionDeliveryService.Proposal(1, "order", ApplicationConstructionDeliveryService.Kind.ENTRY,
                    "订单登记", List.of(), List.of(), List.of());
            delivery.confirm(planId, new ApplicationConstructionDeliveryService.Command(UUID.randomUUID().toString(), entry, delivery.preview(planId, entry).fingerprint()));
            // Configuration readiness is distinct from acceptance of actual tenant business behavior.
            var progress = delivery.progress(planId, "order");
            assertThat(progress.pagePublished()).isTrue();
            assertThat(progress.entryVisible()).isTrue();
            assertThat(progress.needsReview()).isFalse();
            assertThat(delivery.task(planId).objects()).filteredOn(item -> item.objectKey().equals("order"))
                    .singleElement().satisfies(item -> assertThat(item.complete()).isFalse());
            assertThat(constructionPlans.read(planId).fieldChanges()).isEmpty();
            var tenant = new net.ximatai.muyun.spring.iam.tenant.Tenant();
            tenant.setTitle("订单业务验收"); tenant.setAlias("sales_" + planId.substring(0, 10));
            String tenantId = tenants.insert(tenant);
            var mvc = webAppContextSetup(webApplicationContext).build();
            // Business acceptance uses the standard HTTP mutation and detail paths in an explicit tenant.
            try (var business = TenantContext.use(tenantId)) {
                var createdCustomer = mvc.perform(post("/" + customer.moduleAlias() + "/insert").contentType("application/json")
                        .content("{\"values\":{\"title\":\"晨光客户\"}}")).andReturn().getResponse();
                assertThat(createdCustomer.getStatus()).as(createdCustomer.getContentAsString()).isEqualTo(201);
                var customerRecord = dynamicRecords.mainEntity(customer.moduleAlias()).list(net.ximatai.muyun.database.core.orm.Criteria.of()
                        .eq("title", "晨光客户"), net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)).getFirst();
                for (String values : List.of("{\"title\":\"纸张\",\"price\":12.5}", "{\"title\":\"墨水\",\"price\":7}", "{\"title\":\"待定价商品\",\"price\":null}")) {
                    var response = mvc.perform(post("/" + product.moduleAlias() + "/insert").contentType("application/json")
                            .content("{\"values\":" + values + "}")).andReturn().getResponse();
                    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
                }
                var products = dynamicRecords.mainEntity(product.moduleAlias());
                var paper = products.list(net.ximatai.muyun.database.core.orm.Criteria.of().eq("title", "纸张"),
                        net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)).getFirst();
                var ink = products.list(net.ximatai.muyun.database.core.orm.Criteria.of().eq("title", "墨水"),
                        net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)).getFirst();
                var resolved = mvc.perform(post("/" + order.moduleAlias() + "/" + child.metadata().getAlias() + "/references/productId/resolve")
                        .contentType("application/json").content("{}")).andReturn().getResponse();
                assertThat(resolved.getStatus()).as(resolved.getContentAsString()).isEqualTo(200);
                var candidates = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resolved.getContentAsString());
                if (candidates.has("data")) candidates = candidates.required("data");
                var prices = new java.util.HashMap<String, java.math.BigDecimal>();
                candidates.required("options").forEach(option -> prices.put(option.required("id").asText(),
                        option.required("affectPatch").required("price").isNull() ? null : option.required("affectPatch").required("price").decimalValue()));
                assertThat(prices).hasSize(3).containsValue(null);
                assertThat(prices.get(paper.getId())).isEqualByComparingTo("12.5");
                assertThat(prices.get(ink.getId())).isEqualByComparingTo("7");
                String body = """
                        {"values":{"number":"SO-001","customerId":"%s"},"children":{"lines":[
                         {"values":{"productId":"%s","quantity":2,"price":%s}}, {"values":{"productId":"%s","quantity":3,"price":%s}}]}}
                        """.formatted(customerRecord.getId(), paper.getId(), prices.get(paper.getId()), ink.getId(), prices.get(ink.getId()));
                var created = mvc.perform(post("/" + order.moduleAlias() + "/insert").contentType("application/json").content(body)).andReturn().getResponse();
                assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
                var records = dynamicRecords.mainEntity(order.moduleAlias());
                var saved = records.select(records.list(net.ximatai.muyun.database.core.orm.Criteria.of().eq("number", "SO-001"),
                        net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)).getFirst().getId());
                assertThat(saved.getTenantId()).isEqualTo(tenantId);
                assertThat(saved.getValue("customerId")).isEqualTo(customerRecord.getId());
                assertThat(new java.math.BigDecimal(saved.getValue("total").toString())).isEqualByComparingTo("46");
                var childRecords = dynamicRecords.entity(order.moduleAlias(), child.metadata().getAlias());
                var lineScope = net.ximatai.muyun.database.core.orm.Criteria.of().eq(child.relation().getForeignKey(), saved.getId());
                var lines = childRecords.list(lineScope, net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10));
                assertThat(lines).hasSize(2).allSatisfy(line -> {
                    var expected = new java.math.BigDecimal(line.getValue("quantity").toString())
                            .multiply(new java.math.BigDecimal(line.getValue("price").toString()));
                    assertThat(new java.math.BigDecimal(line.getValue("amount").toString())).isEqualByComparingTo(expected);
                });
                var repriced = mvc.perform(post("/" + product.moduleAlias() + "/update/" + paper.getId())
                        .contentType("application/json").content("""
                        {"version":%d,"values":{"title":"纸张新价","price":20}}
                        """.formatted(paper.getVersion()))).andReturn().getResponse();
                assertThat(repriced.getStatus()).as(repriced.getContentAsString()).isEqualTo(200);
                var retained = lines.stream().filter(line -> paper.getId().equals(line.getValue("productId"))).findFirst().orElseThrow();
                // A non-price edit carries the stored snapshot; the selection effect must not run during save.
                String unchangedLines = lines.stream().map(line -> """
                        {"id":"%s","version":%d,"values":{"productId":"%s","quantity":%s,"price":%s}}
                        """.formatted(line.getId(), line.getVersion(), line.getValue("productId"), line.getValue("quantity"), line.getValue("price")))
                        .collect(java.util.stream.Collectors.joining(","));
                var renamed = mvc.perform(post("/" + order.moduleAlias() + "/update/" + saved.getId()).contentType("application/json").content("""
                        {"version":%d,"values":{"number":"SO-001-note","customerId":"%s"},"children":{"lines":[%s]}}
                        """.formatted(saved.getVersion(), customerRecord.getId(), unchangedLines))).andReturn().getResponse();
                assertThat(renamed.getStatus()).as(renamed.getContentAsString()).isEqualTo(200);
                saved = records.select(saved.getId());
                retained = childRecords.select(retained.getId());
                assertThat(new java.math.BigDecimal(retained.getValue("price").toString())).isEqualByComparingTo("12.5");
                assertThat(new java.math.BigDecimal(saved.getValue("total").toString())).isEqualByComparingTo("46");
                String update = """
                        {"version":%d,"values":{"number":"SO-001","customerId":"%s"},"children":{"lines":[
                         {"id":"%s","version":%d,"values":{"productId":"%s","quantity":4,"price":12.5}}]}}
                        """.formatted(saved.getVersion(), customerRecord.getId(), retained.getId(), retained.getVersion(), paper.getId());
                var updated = mvc.perform(post("/" + order.moduleAlias() + "/update/" + saved.getId())
                        .contentType("application/json").content(update)).andReturn().getResponse();
                assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
                var reopened = records.select(saved.getId());
                assertThat(new java.math.BigDecimal(reopened.getValue("total").toString())).isEqualByComparingTo("50");
                String retainedId = retained.getId();
                assertThat(childRecords.list(lineScope, net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)))
                        .singleElement().satisfies(line -> {
                    assertThat(line.getId()).isEqualTo(retainedId);
                    assertThat(new java.math.BigDecimal(line.getValue("amount").toString())).isEqualByComparingTo("50");
                });
                var viewed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/" + order.moduleAlias() + "/view/" + saved.getId())).andReturn().getResponse();
                assertThat(viewed.getStatus()).as(viewed.getContentAsString()).isEqualTo(200);
                assertThat(viewed.getContentAsString()).contains("SO-001", "晨光客户");
            }
            var acceptance = delivery.previewAcceptance(planId, "order");
            delivery.confirmAcceptance(planId, new ApplicationConstructionDeliveryService.AcceptanceCommand(
                    UUID.randomUUID().toString(), "order", acceptance.fingerprint()));
            assertThat(delivery.task(planId).objects()).filteredOn(item -> item.objectKey().equals("order"))
                    .singleElement().satisfies(item -> assertThat(item.complete()).isTrue());
        }
    }

    private static MetadataFieldChangeSetDraft governedField(String name, String spec) {
        var field = new MetadataField(); field.setFieldName(name); field.setColumnName(name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase());
        field.setTitle(name); field.setFieldSpecAlias(spec);
        return new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null, field);
    }

    private void applyGovernedFields(String module, String relation, int version, List<MetadataFieldChangeSetDraft> fields) {
        var changes = new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(
                relation, version, java.util.Map.of(), fields)), List.of(), List.of());
        var preview = metadataPreviews.preview(module, changes);
        assertThat(preview.errors()).isEmpty();
        metadataPublisher.apply(module, new MetadataModelChangeSetApplyCommand(changes, preview.proposalFingerprint()));
    }

    private record PageLayout(String objectKey, String title, List<String> listFields, List<String> formFields,
                              List<String> searchFields, java.util.Map<String, List<String>> childFields) {
        PageLayout(String objectKey, String title, List<String> listFields, List<String> formFields, List<String> searchFields) {
            this(objectKey, title, listFields, formFields, searchFields, java.util.Map.of());
        }
    }
    private record PublishedPage(String pageId, String variantId, String revisionId) {}

    /** Test data enters the same standard page/compiler/publication services as the visual editor. */
    private PublishedPage publishStandardPage(String planId, PageLayout layout) {
        return new TransactionTemplate(transactions).execute(status -> {
            var binding = constructionPlans.read(planId).moduleBindings().stream()
                    .filter(item -> item.objectKey().equals(layout.objectKey())).findFirst().orElseThrow();
            var mainRelation = moduleRelations.list(net.ximatai.muyun.database.core.orm.Criteria.of()
                    .eq("moduleAlias", binding.moduleAlias()).eq("relationRole", RelationRole.MAIN)).getFirst();
            var page = constructionPages.resolveGlobalPage(binding.moduleAlias(), "management").orElse(null);
            String pageId;
            if (page == null) {
                page = new PlatformPageDefinition(); page.setModuleAlias(binding.moduleAlias()); page.setAlias("management");
                page.setMainRelationId(mainRelation.getId()); page.setContractType(PlatformPageContractType.MANAGEMENT);
                page.setEnabled(true); page.setTitle(layout.title()); pageId = constructionPages.insert(page);
            } else pageId = page.getId();
            var variants = presentationVariants.list(net.ximatai.muyun.database.core.orm.Criteria.of().eq("pageId", pageId));
            String variantId;
            if (variants.isEmpty()) {
                var variant = new PlatformPresentationVariant(); variant.setPageId(pageId); variant.setTitle(layout.title());
                variant.setEnabled(true); variant.setClientType(PlatformPresentationClientType.WEB); variant.setScopeType(PlatformPresentationScopeType.GLOBAL);
                variantId = presentationVariants.insert(variant);
            } else variantId = variants.getFirst().getId();
            int number = presentationRevisions.list(net.ximatai.muyun.database.core.orm.Criteria.of().eq("variantId", variantId))
                    .stream().mapToInt(PlatformPresentationRevision::getRevisionNo).max().orElse(0) + 1;
            var revision = new PlatformPresentationRevision(); revision.setVariantId(variantId); revision.setRevisionNo(number);
            revision.setTitle(layout.title()); revision.setEnabled(true); revision.setStatus(PlatformPresentationRevisionStatus.DRAFT);
            revision.setTemplateAlias("management"); revision.setTemplateVersion(2);
            try {
                revision.setUiTreeJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                        "template", "management", "templateVersion", 2, "mode", "LIST_CARD", "quickSearchFields", layout.searchFields(),
                        "nodes", List.of(java.util.Map.of("slot", "list", "title", layout.title(), "fields", layout.listFields()),
                                java.util.Map.of("slot", "form", "title", layout.title(), "fields", layout.formFields(), "relations",
                                        layout.childFields().entrySet().stream().map(entry -> java.util.Map.of(
                                                "relation", entry.getKey(), "title", entry.getKey(), "fields", entry.getValue())).toList())))));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
            runtimeContexts.previewDynamicPageDescriptor(page, revision, revision.getUiTreeJson());
            String revisionId = presentationRevisions.insert(revision);
            presentationPublisher.publish(revisionId);
            return new PublishedPage(pageId, variantId, revisionId);
        });
    }

    private static PlatformPresentationRevision standardRevision(String variantId, int number, String groupTitle) {
        var revision = new PlatformPresentationRevision(); revision.setVariantId(variantId); revision.setRevisionNo(number);
        revision.setTitle("订单页面"); revision.setEnabled(true); revision.setStatus(PlatformPresentationRevisionStatus.DRAFT);
        revision.setTemplateAlias("management"); revision.setTemplateVersion(PlatformPresentationTemplateCatalog.MODE_AWARE_VERSION);
        revision.setUiTreeJson("""
                {"template":"management","templateVersion":2,"mode":"LIST_CARD","quickSearchFields":[],"nodes":[
                  {"slot":"list","title":"订单列表","fields":["orderNumber"]},
                  {"slot":"form","title":"订单表单","fields":[],"groups":[{"group":"basic","title":"%s","fields":[{"field":"orderNumber","props":{"label":"订单号"}}]}]}
                ]}
                """.formatted(groupTitle));
        return revision;
    }

    private record FieldDraft(String name, String title, String specAlias, boolean required, boolean unique,
                              boolean indexed, MetadataFieldReferenceConfigDraft reference, boolean titleField) {}

    private void publishFields(String planId, String objectKey, List<FieldDraft> fields) {
        var binding = currentBindings(planId).stream().filter(item -> item.objectKey().equals(objectKey)).findFirst().orElseThrow();
        var changes = fieldChanges(planId, objectKey, fields);
        var preview = metadataPreviews.preview(binding.moduleAlias(), changes);
        assertThat(preview.errors()).isEmpty();
        metadataPublisher.apply(binding.moduleAlias(), new MetadataModelChangeSetApplyCommand(changes, preview.proposalFingerprint()));
    }

    private MetadataModelChangeSetPreviewCommand fieldChanges(String planId, String objectKey, List<FieldDraft> fields) {
        var binding = currentBindings(planId).stream().filter(item -> item.objectKey().equals(objectKey)).findFirst().orElseThrow();
        var drafts = fields.stream().map(value -> {
            var field = governedField(value.name(), value.specAlias()).field(); field.setTitle(value.title());
            field.setMetadataId(binding.metadataId());
            field.setRequired(value.required()); field.setUniqueField(value.unique()); field.setIndexed(value.indexed());
            field.setTitleField(value.titleField());
            return new MetadataFieldChangeSetDraft(MetadataFieldChangeSetDraft.Operation.ADD, null, null, field,
                    value.reference() == null ? null : new MetadataFieldPropertyDraft(MetadataFieldPropertyKind.MODULE_REFERENCE, null, value.reference(), null));
        }).toList();
        return new MetadataModelChangeSetPreviewCommand(List.of(new MetadataModelRelationChangeSetDraft(binding.relationId(),
                metadataService.select(binding.metadataId()).getVersion(), java.util.Map.of(), drafts)), List.of(), List.of());
    }

    private List<ApplicationConstructionFieldService.Binding> currentBindings(String planId) {
        return constructionPlans.read(planId).moduleBindings().stream().map(link -> {
            var relation = moduleRelations.list(net.ximatai.muyun.database.core.orm.Criteria.of()
                    .eq("moduleAlias", link.moduleAlias()).eq("relationRole", RelationRole.MAIN)).getFirst();
            return new ApplicationConstructionFieldService.Binding(link.objectKey(), link.moduleAlias(), relation.getMetadataId(), relation.getId());
        }).toList();
    }

    /** Fixtures use the same separate standard services as the management pages, never retired builders. */
    private void createStandardPlan(String planId, ApplicationConstructionPlanContent content, String app, java.util.Map<String, String> moduleNames) {
        if (applications.select(app) == null) {
            var application = new Application(); application.setAlias(app); application.setTitle(content.title());
            applications.insert(application);
        }
        var objects = content.objects().stream().map(object -> {
            String name = moduleNames.getOrDefault(object.key(), object.key());
            String alias = app + "." + name;
            var module = new PlatformModule(); module.setApplicationAlias(app); module.setAlias(alias);
            module.setTitle(object.name()); module.setModuleKind(ModuleKind.DYNAMIC); modules.insert(module);
            orchestration.createMainMetadata(alias, new ModuleMainMetadataCreateCommand(name, object.name(), "public", null, false));
            return new ApplicationConstructionPlanContent.BusinessObject(object.key(), object.name(), object.purpose(), alias);
        }).toList();
        var linked = new ApplicationConstructionPlanContent(content.title(), content.goal(), content.inScope(), content.outOfScope(), objects,
                content.relationships(), content.rules(), content.questions(), content.assumptions(), content.decisions(), content.acceptanceExamples(), content.requirements());
        constructionPlans.confirm(planId, new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 0, linked));
        assertThat(constructionPlans.read(planId).initializations()).isEmpty();
    }

}
