package net.ximatai.muyun.spring.demo.school.test;

import net.ximatai.muyun.spring.boot.MuYunSpringApplication;
import net.ximatai.muyun.spring.platform.application.*;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.database.core.IDatabaseOperations;
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
    @Autowired ApplicationConstructionPlanService constructionPlans;
    @Autowired ApplicationConstructionInitializationService construction;
    @Autowired ApplicationConstructionFieldService constructionFields;
    @Autowired ApplicationConstructionDeliveryService delivery;
    @Autowired MetadataService metadataService;
    @Autowired MetadataModelChangeSetPreviewService metadataPreviews;
    @Autowired MetadataModelChangeSetApplyService metadataPublisher;
    @Autowired net.ximatai.muyun.spring.iam.tenant.TenantService tenants;
    @Autowired net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService dynamicRecords;
    @Autowired IDatabaseOperations<?> constructionDatabase;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired net.ximatai.muyun.spring.platform.web.PlatformModuleRuntimeContextService runtimeContexts;
    @Autowired net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService constructionPages;

    @Test void recognizesStandardMetadataPublicationWithoutAssistantFieldReceipts() {
        String planId = UUID.randomUUID().toString().replace("-", "");
        var content = new ApplicationConstructionPlanContent("订单", "登记订单", List.of("登记订单号"), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("entry", "订单", "登记")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("录入订单号"),
                List.of(new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0,
                        "entry", ApplicationConstructionRequirement.Mode.REQUIRED, "orderNumber", "订单号必填")));
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员"))) {
            constructionPlans.confirm(planId, new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 0, content));
            var initial = new ApplicationConstructionInitializationService.Proposal(1, "entry", "manual" + planId.substring(0, 12), "订单应用", "registration_records");
            construction.confirm(planId, new ApplicationConstructionInitializationService.ConfirmCommand(
                    UUID.randomUUID().toString(), initial, construction.preview(planId, initial).fingerprint()));
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.CONFIGURE_FIELDS);
            var binding = constructionPlans.read(planId).initializations().getFirst();
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
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.PUBLISH_PAGE);
            var page = new ApplicationConstructionDeliveryService.Proposal(1, "entry", ApplicationConstructionDeliveryService.Kind.PAGE,
                    "订单登记", List.of("orderNumber"), List.of("orderNumber"), List.of("orderNumber"));
            delivery.confirm(planId, new ApplicationConstructionDeliveryService.Command(UUID.randomUUID().toString(), page, delivery.preview(planId, page).fingerprint()));
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.CREATE_ENTRY);
            var entry = new ApplicationConstructionDeliveryService.Proposal(1, "entry", ApplicationConstructionDeliveryService.Kind.ENTRY,
                    "订单登记", List.of(), List.of(), List.of());
            delivery.confirm(planId, new ApplicationConstructionDeliveryService.Command(UUID.randomUUID().toString(), entry, delivery.preview(planId, entry).fingerprint()));
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.VERIFY_BUSINESS);
            var acceptance = delivery.previewAcceptance(planId, "entry");
            delivery.confirmAcceptance(planId, new ApplicationConstructionDeliveryService.AcceptanceCommand(UUID.randomUUID().toString(), "entry", acceptance.fingerprint()));
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.COMPLETE);
            assertThat(constructionPlans.read(planId).fieldChanges()).isEmpty();
        }
    }

    @Test void commitsFieldsAndReceiptAtomicallyWithRepeatableConfirmation() throws Exception {
        String planId = UUID.randomUUID().toString().replace("-", "");
        String app = "field" + planId.substring(0, 12);
        var content = new ApplicationConstructionPlanContent("订单", "登记订单", List.of("录入", "记录备注"), List.of("审批"),
            List.of(new ApplicationConstructionPlanContent.BusinessObject("entry", "订单", "登记")),
            List.of(), List.of("订单号必填且不重复"), List.of(), List.of(), List.of(), List.of("录入并查询"), List.of(new net.ximatai.muyun.spring.platform.application.ApplicationConstructionRequirement(
                net.ximatai.muyun.spring.platform.application.ApplicationConstructionRequirement.Section.SCOPE, 0, "entry",
                net.ximatai.muyun.spring.platform.application.ApplicationConstructionRequirement.Mode.MANUAL, "", "实际录入并查询一笔订单"),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0, "entry", ApplicationConstructionRequirement.Mode.REQUIRED, "orderNumber", "订单号由系统要求填写"),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0, "entry", ApplicationConstructionRequirement.Mode.UNIQUE, "orderNumber", "系统拒绝重复订单号"),
                new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 1, "entry", ApplicationConstructionRequirement.Mode.FIELD, "remark", "可选填备注")));
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员")); var scope = TenantContext.system("field acceptance")) {
            constructionPlans.confirm(planId, new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 0, content));
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.INITIALIZE);
            var proposal = new ApplicationConstructionInitializationService.Proposal(1, "entry", app, "订单应用", "registration_records");
            var preview = construction.preview(planId, proposal);
            var result = construction.confirm(planId, new ApplicationConstructionInitializationService.ConfirmCommand(UUID.randomUUID().toString(), proposal, preview.fingerprint()));
            MockMvc mvc = webAppContextSetup(webApplicationContext).build();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var description = constructionFields.describe(planId, "entry");
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.CONFIGURE_FIELDS);
            assertThatThrownBy(() -> delivery.preview(planId, new ApplicationConstructionDeliveryService.Proposal(1, "entry",
                    ApplicationConstructionDeliveryService.Kind.PAGE, "订单", List.of("orderNumber"), List.of("orderNumber"), List.of())))
                    .hasMessageContaining("尚未落实到实际字段约束");
            String spec = description.specs().stream().filter(value -> value.type().equals("STRING")).findFirst().orElseThrow().alias();
            var fieldProposal = new net.ximatai.muyun.spring.platform.application.ApplicationConstructionFieldService.Proposal(1, "entry", description.metadataVersion(),
                    List.of(new net.ximatai.muyun.spring.platform.application.ApplicationConstructionFieldService.Field("orderNumber", "订单号", spec, true, true, true), new ApplicationConstructionFieldService.Field("remark", "备注", spec, false, false, false)));
            var fieldPreview = constructionFields.preview(planId, fieldProposal);
            assertThat(fieldPreview.errors()).isEmpty();
            var fieldCommand = new net.ximatai.muyun.spring.platform.application.ApplicationConstructionFieldService.Command(UUID.randomUUID().toString(), fieldProposal, fieldPreview.fingerprint());
            assertThat(constructionPlans.read(planId).fieldChanges()).isEmpty();
            assertThatThrownBy(() -> constructionFields.confirm(planId, new net.ximatai.muyun.spring.platform.application.ApplicationConstructionFieldService.Command(fieldCommand.requestId(), fieldProposal, "0".repeat(64))))
                    .hasMessageContaining("预检已过期");
            new TransactionTemplate(transactions).executeWithoutResult(status -> { constructionFields.confirm(planId, fieldCommand); status.setRollbackOnly(); });
            assertThat(constructionFields.status(planId, fieldCommand.requestId())).isNull();
            assertThat(constructionDatabase.query("select column_name from information_schema.columns where table_schema = 'public' and table_name = ? and column_name = 'order_number'", preview.tableName())).isEmpty();
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var writes = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                    try (var identity = CurrentUserContext.use(CurrentUser.systemUser("construction-admin", "建设管理员"))) {
                        return constructionFields.confirm(planId, fieldCommand);
                    }
                })).toList();
                for (var write : writes) assertThat(write.get(15, java.util.concurrent.TimeUnit.SECONDS).receipt().fields()).hasSize(2);
            }
            var fieldHttp = mvc.perform(post("/platform.application-construction-plans/" + planId + "/field-changes")
                    .contentType("application/json").content(json.writeValueAsString(fieldCommand))).andReturn().getResponse();
            assertThat(fieldHttp.getStatus()).as(fieldHttp.getContentAsString()).isEqualTo(200);
            assertThat(constructionFields.status(planId, fieldCommand.requestId()).runtime().status()).isEqualTo("ACTIVE");
            assertThat(constructionPlans.read(planId).fieldChanges()).hasSize(1);
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.PUBLISH_PAGE);
            assertThat(constructionDatabase.query("select column_name from information_schema.columns where table_schema = 'public' and table_name = ? and column_name = 'order_number'", preview.tableName())).hasSize(1);
            var duplicateProposal = new net.ximatai.muyun.spring.platform.application.ApplicationConstructionFieldService.Proposal(1, "entry", metadataService.select(result.receipt().metadataId()).getVersion(), fieldProposal.fields());
            assertThat(constructionFields.preview(planId, duplicateProposal).errors()).isNotEmpty();
            var pageProposal = new ApplicationConstructionDeliveryService.Proposal(1, "entry", ApplicationConstructionDeliveryService.Kind.PAGE,
                    "订单登记", List.of("orderNumber"), List.of("orderNumber"), List.of("orderNumber"));
            assertThatThrownBy(() -> delivery.preview(planId, pageProposal)).hasMessageContaining("包含选填字段");
            var completePageProposal = new ApplicationConstructionDeliveryService.Proposal(1, "entry", ApplicationConstructionDeliveryService.Kind.PAGE,
                    "订单登记", List.of("orderNumber"), List.of("orderNumber", "remark"), List.of("orderNumber"));
            var pagePreview = delivery.preview(planId, completePageProposal);
            var pageCommand = new ApplicationConstructionDeliveryService.Command(UUID.randomUUID().toString(), completePageProposal, pagePreview.fingerprint());
            assertThat(delivery.progress(planId, "entry").pagePublished()).isFalse();
            new TransactionTemplate(transactions).executeWithoutResult(status -> { delivery.confirm(planId, pageCommand); status.setRollbackOnly(); });
            assertThat(delivery.result(planId, pageCommand.requestId())).isNull();
            assertThat(delivery.progress(planId, "entry").pagePublished()).isFalse();
            var pageReceipt = delivery.confirm(planId, pageCommand);
            assertThat(delivery.confirm(planId, pageCommand)).isEqualTo(pageReceipt);
            assertThat(delivery.progress(planId, "entry").pagePublished()).isTrue();
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.CREATE_ENTRY);
            var installedContext = runtimeContexts.context(result.receipt().moduleAlias());
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
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.VERIFY_BUSINESS);
            assertThat(delivery.progress(planId, "entry").needsReview()).isFalse();
            assertThatThrownBy(() -> delivery.preview(planId, entryProposal)).hasMessageContaining("已有访问入口");
            var businessTenant = new net.ximatai.muyun.spring.iam.tenant.Tenant();
            businessTenant.setTitle("建设验收租户"); businessTenant.setAlias("accept_" + planId.substring(0, 10));
            String tenantId = tenants.insert(businessTenant);
            try (var businessScope = TenantContext.use(tenantId)) {
            String module = result.receipt().moduleAlias();
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
            assertThat(mvc.perform(post("/" + module + "/insert").contentType("application/json")
                    .content("{\"values\":{\"orderNumber\":\"ACCEPT-001\"}}" )).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
            assertThat(mvc.perform(post("/" + module + "/insert").contentType("application/json")
                    .content("{\"values\":{}}" )).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
            }
            assertThat(delivery.progress(planId, "entry").acceptanceConfirmed()).isFalse();
            var acceptancePreview = delivery.previewAcceptance(planId, "entry");
            var acceptanceCommand = new ApplicationConstructionDeliveryService.AcceptanceCommand(UUID.randomUUID().toString(), "entry", acceptancePreview.fingerprint());
            var acceptanceReceipt = delivery.confirmAcceptance(planId, acceptanceCommand);
            assertThat(delivery.confirmAcceptance(planId, acceptanceCommand)).isEqualTo(acceptanceReceipt);
            assertThat(delivery.progress(planId, "entry").acceptanceConfirmed()).isTrue();
            assertThat(delivery.task(planId).objects().getFirst().stage()).isEqualTo(ApplicationConstructionDeliveryService.TaskStage.COMPLETE);
            var changedPage = constructionPages.select(pageReceipt.pageId());
            changedPage.setTitle("调整后的订单登记");
            constructionPages.update(changedPage);
            assertThat(delivery.progress(planId, "entry").acceptanceConfirmed()).isFalse();

            constructionPlans.confirm(planId, new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 1,
                new ApplicationConstructionPlanContent("修订订单", content.goal(), content.inScope(), content.outOfScope(), content.objects(), content.relationships(), content.rules(), content.questions(), content.assumptions(), content.decisions(), content.acceptanceExamples(), content.requirements())));
            assertThat(constructionFields.confirm(planId, fieldCommand).receipt().fields()).hasSize(2);
            assertThat(delivery.progress(planId, "entry").needsReview()).isTrue();
            assertThat(delivery.progress(planId, "entry").acceptanceConfirmed()).isFalse();
            assertThatThrownBy(() -> delivery.previewAcceptance(planId, "entry")).hasMessageContaining("尚未就绪");
            assertThatThrownBy(() -> constructionFields.preview(planId, fieldProposal)).hasMessageContaining("需求版本已变化");
            try (var other = CurrentUserContext.use(CurrentUser.systemUser("other-owner", "其他用户"))) {
                assertThatThrownBy(() -> constructionFields.status(planId, fieldCommand.requestId())).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            }
        }
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("tenant-user", "用户", "tenant"))) {
            assertThatThrownBy(() -> constructionFields.describe(planId, "entry")).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        }
    }
}
