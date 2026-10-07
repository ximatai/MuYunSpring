package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.menu.*;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import net.ximatai.muyun.spring.platform.ui.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationConstructionDeliveryServiceTest {
    @Test void configurationProgressDoesNotClaimBusinessRecordsAreAbsentOrVerified() throws Exception {
        for (boolean accepted : List.of(false, true)) {
            var progress = new ApplicationConstructionDeliveryService.Progress("order", "sample.order", "ACTIVE", true,
                    true, "menu", false, accepted, List.of(), List.of(), List.of());
            var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(progress);
            assertThat(json.path("businessDataStatus").asText()).isEqualTo("NOT_QUERIED");
            assertThat(json.path("acceptanceConfirmed").asBoolean()).isEqualTo(accepted);
        }
    }

    @Test void retiredPageAndMenuWritesRejectBeforeTouchingConfigurationOrReceipts() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var database = mock(IDatabaseOperations.class);
        var receipts = mock(ApplicationConstructionDeliveryDao.class);
        var menus = mock(MenuService.class);
        var service = new ApplicationConstructionDeliveryService(database, plans,
                mock(ApplicationConstructionFieldService.class), receipts,
                mock(ApplicationConstructionAcceptanceDao.class), mock(PlatformPageDefinitionService.class),
                mock(PlatformPresentationVariantService.class), mock(PlatformPresentationRevisionResolver.class), menus,
                mock(DynamicRuntimeActivationService.class));
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("restricted", "受限管理员"))) {
            assertThatThrownBy(() -> service.preview("plan", new ApplicationConstructionDeliveryService.Proposal(1, "order", ApplicationConstructionDeliveryService.Kind.PAGE, "订单", List.of("number"), List.of("number"), List.of())))
                    .hasMessageContaining("标准页面编排");
            assertThatThrownBy(() -> service.preview("plan", new ApplicationConstructionDeliveryService.Proposal(1, "order", ApplicationConstructionDeliveryService.Kind.ENTRY, "订单", List.of(), List.of(), List.of())))
                    .hasMessageContaining("共享菜单治理");
            for (var kind : ApplicationConstructionDeliveryService.Kind.values()) {
                var proposal = new ApplicationConstructionDeliveryService.Proposal(1, "order", kind,
                        "订单", kind == ApplicationConstructionDeliveryService.Kind.PAGE ? List.of("number") : List.of(),
                        kind == ApplicationConstructionDeliveryService.Kind.PAGE ? List.of("number") : List.of(), List.of());
                assertThatThrownBy(() -> service.confirm("plan", new ApplicationConstructionDeliveryService.Command(
                        "old-request-00000001", proposal, "old-fingerprint")))
                        .hasMessageContaining("历史结果仅支持查询");
            }
            // An old caller still queries only its original receipt, without creating a new one.
            assertThat(service.result("plan", "old-request-00000001")).isNull();
        }
        verify(plans).read("plan");
        verify(receipts).findById(anyString());
        verifyNoMoreInteractions(plans, receipts);
        verifyNoInteractions(database, menus);
    }
    @Test void choicesFollowEvidenceWithoutForcingOptionalWritesOrSerializingIndependentObjects() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var publisher = mock(PlatformPresentationRevisionPublishService.class);
        var menus = mock(MenuService.class);
        var service = spy(new ApplicationConstructionDeliveryService(mock(IDatabaseOperations.class), plans,
                mock(ApplicationConstructionFieldService.class), mock(ApplicationConstructionDeliveryDao.class),
                mock(ApplicationConstructionAcceptanceDao.class), mock(PlatformPageDefinitionService.class),
                mock(PlatformPresentationVariantService.class), mock(PlatformPresentationRevisionResolver.class), menus,
                mock(DynamicRuntimeActivationService.class)));
        var content = new ApplicationConstructionPlanContent("登记", "登记两个独立对象", List.of("记录基本信息"), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("first", "对象一", "登记", "sample.first"),
                        new ApplicationConstructionPlanContent.BusinessObject("second", "对象二", "登记")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(plans.read("plan")).thenReturn(new ApplicationConstructionPlanService.Snapshot("plan", 1, content,
                java.time.Instant.EPOCH, "LINKED", List.of(), List.of(), List.of(), List.of()));
        doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "ACTIVE", false,
                false, null, false, false, List.of(), List.of(), List.of())).when(service).progress("plan", "first");
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("admin", "管理员"))) {
            var task = service.task("plan");
            assertThat(task.objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.PUBLISH_PAGE);
            assertThat(task.objects().get(1).options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.INITIALIZE);
            assertThat(task.objects().get(1).requirements()).isEmpty();
            assertThat(task.unmappedRequirements()).anyMatch(item ->
                    item.status() == ApplicationConstructionRequirements.Status.UNMAPPED);
            doThrow(new IllegalArgumentException("关联模块尚无可用主实体")).when(service).progress("plan", "first");
            assertThat(service.task("plan").objects().getFirst().options())
                    .extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.REVIEW_CONFIGURATION);

            assertThat(task.objects().get(1).options().getFirst().explanation()).contains("逐页准备可见表单");
            doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "ACTIVE", true,
                    true, "menu", false, false, List.of(), List.of(), List.of())).when(service).progress("plan", "first");
            assertThat(service.task("plan").objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.VERIFY_BUSINESS);
            assertThat(service.task("plan").objects().getFirst().complete()).isFalse();
            doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "ACTIVE", true,
                    true, "menu", true, false, List.of(), List.of(), List.of())).when(service).progress("plan", "first");
            assertThat(service.task("plan").objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.REVIEW_CONFIGURATION);
            doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "PENDING", false,
                    false, null, false, false, List.of(), List.of(), List.of())).when(service).progress("plan", "first");
            assertThat(service.task("plan").objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.VERIFY_RUNTIME);
            var unresolved = new ApplicationConstructionPlanContent(content.title(), content.goal(), content.inScope(),
                    content.outOfScope(), content.objects(), content.relationships(), content.rules(), List.of("其他对象的规则待商定"),
                    content.assumptions(), content.decisions(), content.acceptanceExamples(), content.requirements());
            var initialized = plans.read("plan");
            when(plans.read("plan")).thenReturn(new ApplicationConstructionPlanService.Snapshot("plan", 1, unresolved,
                    java.time.Instant.EPOCH, "INITIALIZED", initialized.initializations(), List.of(), List.of(), List.of()));
            doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "ACTIVE", false,
                    false, null, false, false, List.of(), List.of(), List.of())).when(service).progress("plan", "first");
            var partial = service.task("plan");
            assertThat(partial.objects().getFirst().progress().pagePublished()).isFalse();
            assertThat(partial.objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.PUBLISH_PAGE);
            // A conversational question is not a platform-wide construction lock.
            assertThat(partial.objects().get(1).options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.INITIALIZE);
            assertThat(plans.read("plan").content().questions()).containsExactly("其他对象的规则待商定");
            doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "ACTIVE", true,
                    true, "menu", false, false, List.of(), List.of(), List.of())).when(service).progress("plan", "first");
            assertThat(service.task("plan").objects().getFirst().options())
                    .extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.REVIEW_REQUIREMENTS);
            assertThatThrownBy(() -> service.previewAcceptance("plan", "first")).hasMessageContaining("未决问题");
            var blocked = List.of(new ApplicationConstructionRequirements.Evidence(ApplicationConstructionRequirement.Section.RULE,
                    0, "自动计算", "first", "", ApplicationConstructionRequirements.Status.UNSUPPORTED, "尚无建设路径"));
            doReturn(new ApplicationConstructionDeliveryService.Progress("first", "sample.first", "ACTIVE", false,
                    false, null, false, false, List.of("自动计算未完成"), List.of(), blocked)).when(service).progress("plan", "first");
            assertThat(service.task("plan").objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.REVIEW_REQUIREMENTS,
                            ApplicationConstructionDeliveryService.TaskAction.REVIEW_CONFIGURATION);
            // External governance changes no longer make a delivered object a construction candidate.
            var original = plans.read("plan");
            when(plans.read("plan")).thenReturn(new ApplicationConstructionPlanService.Snapshot("plan", 1, content,
                    java.time.Instant.EPOCH, "PARTIALLY_DELIVERED", original.initializations(), List.of(), List.of(), List.of("first")));
            var resumed = service.task("plan");
            assertThat(resumed.objects().getFirst().complete()).isTrue();
            assertThat(resumed.objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.REVIEW_CURRENT_CONFIGURATION);
            assertThat(resumed.objects().getFirst().requirements()).isEmpty();
            assertThat(resumed.objects().get(1).options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.INITIALIZE);
            assertThatThrownBy(() -> service.preview("plan", new ApplicationConstructionDeliveryService.Proposal(1, "first", ApplicationConstructionDeliveryService.Kind.PAGE, "旧页面", List.of("title"), List.of("title"), List.of())))
                    .hasMessageContaining("标准页面编排");
        }
        verifyNoInteractions(publisher, menus);
    }

    @Test void unmappedRequirementsArePlanFactsAndDoNotGrowWithTheNumberOfObjects() throws Exception {
        var plans = mock(ApplicationConstructionPlanService.class);
        var service = spy(new ApplicationConstructionDeliveryService(mock(IDatabaseOperations.class), plans,
                mock(ApplicationConstructionFieldService.class), mock(ApplicationConstructionDeliveryDao.class),
                mock(ApplicationConstructionAcceptanceDao.class), mock(PlatformPageDefinitionService.class),
                mock(PlatformPresentationVariantService.class), mock(PlatformPresentationRevisionResolver.class), mock(MenuService.class),
                mock(DynamicRuntimeActivationService.class)));
        var objects = java.util.stream.IntStream.range(0, 7).mapToObj(index ->
                new ApplicationConstructionPlanContent.BusinessObject("object" + index, "对象" + index, "登记",
                        index == 0 ? "sample.first" : null)).toList();
        var requirements = java.util.stream.IntStream.range(0, 10).mapToObj(index ->
                "要求" + index + "：" + "记录共享业务事实并核对保存结果".repeat(20)).toList();
        var content = new ApplicationConstructionPlanContent("登记", "多对象完整任务", requirements, List.of(), objects,
                requirements, requirements, List.of(), List.of(), List.of(), List.of(), List.of());
        when(plans.read("plan")).thenReturn(new ApplicationConstructionPlanService.Snapshot("plan", 1, content,
                java.time.Instant.EPOCH, "LINKED", List.of(), List.of(), List.of(), List.of()));
        var evidence = ApplicationConstructionRequirements.evaluate(content, "object0", List.of());
        doReturn(new ApplicationConstructionDeliveryService.Progress("object0", "sample.first", "ACTIVE", true,
                true, "menu", false, false, List.of("要求尚未兑现"), List.of(), evidence))
                .when(service).progress("plan", "object0");
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("admin", "管理员"))) {
            var task = service.task("plan");
            assertThat(task.unmappedRequirements()).hasSize(30).allSatisfy(item -> {
                assertThat(item.objectKey()).isEmpty();
                assertThat(item.status()).isEqualTo(ApplicationConstructionRequirements.Status.UNMAPPED);
            });
            assertThat(task.objects()).allSatisfy(object -> {
                assertThat(object.requirements()).isEmpty();
                assertThat(object.complete()).isFalse();
            });
            // Compaction cannot turn unresolved requirements into permission to publish or accept.
            assertThat(task.objects().getFirst().options()).extracting(ApplicationConstructionDeliveryService.TaskOption::action)
                    .containsExactly(ApplicationConstructionDeliveryService.TaskAction.REVIEW_REQUIREMENTS,
                            ApplicationConstructionDeliveryService.TaskAction.REVIEW_CONFIGURATION);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var json = mapper.valueToTree(task);
            assertThat(json.path("objects").get(0).path("progress").has("requirements")).isFalse();
            assertThat(json.path("objects").get(0).path("progress").path("businessDataStatus").asText()).isEqualTo("NOT_QUERIED");
            assertThat(mapper.writeValueAsString(task).length()).isLessThan(20_000);
        }
    }
}
