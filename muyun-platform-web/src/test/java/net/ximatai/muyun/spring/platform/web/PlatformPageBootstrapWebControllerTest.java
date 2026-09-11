package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogEvent;
import net.ximatai.muyun.spring.platform.menu.MenuPageMode;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.ui.PlatformActionBlock;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrap;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrapService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageEntryContext;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformPageBootstrapWebControllerTest {
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldExposeStaticMenuBootstrapThroughTheSharedPageEntryEndpoint() throws Exception {
        PlatformPageBootstrapService bootstrapService = mock(PlatformPageBootstrapService.class);
        PlatformModuleRuntimeContextService runtimeContextService = mock(PlatformModuleRuntimeContextService.class);
        ActiveTenantVerifier activeTenantVerifier = mock(ActiveTenantVerifier.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformPageBootstrapWebController(
                bootstrapService, runtimeContextService, activeTenantVerifier)).build();
        PlatformPageBootstrap bootstrap = new PlatformPageBootstrap(
                new PlatformPageEntryContext("menu-1", "iam.organization", MenuPageMode.LIST,
                        "organization-list", "enabled-organizations", null),
                PlatformUiClientType.WEB,
                new PlatformResolvedPageConfig(List.of(), List.of(), List.of(), List.of(), List.of(
                        new PlatformActionBlock("organization-list", "action", null, "create", null, "toolbar",
                                null, null, null, null, null, null, "PRIMARY"),
                        new PlatformActionBlock("organization-list", "action", null, "delete", null, "toolbar")
                ), List.of())
        );
        when(bootstrapService.bootstrapByMenu("menu-1", PlatformUiClientType.WEB)).thenReturn(bootstrap);
        when(runtimeContextService.context("iam.organization")).thenReturn(new PlatformModuleRuntimeContext(
                "iam.organization", "组织管理", ModuleKind.STATIC, ModuleEntryType.ROUTE, null, null,
                "organization", Set.of(EntityCapability.CRUD), List.of(), Set.of("crud"), List.of(
                        action("create", true), action("delete", false)), null));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(get("/platform.menu/menu-1/entry"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entry.moduleAlias").value("iam.organization"))
                    .andExpect(jsonPath("$.mainEntityAlias").value("organization"))
                    .andExpect(jsonPath("$.resolvedConfig.actionBlocks.length()").value(1))
                    .andExpect(jsonPath("$.resolvedConfig.actionBlocks[0].actionCode").value("create"))
                    .andExpect(jsonPath("$.resolvedConfig.actionBlocks[0].importance").value("PRIMARY"))
                    .andExpect(jsonPath("$.openApiPath").value("/iam.organization/openapi"));
        }

        verify(activeTenantVerifier).verifyActiveTenant("tenant-a");
    }

    @Test
    void shouldExposeMenuBootstrapForSystemUsersWithoutRequiringTenantContext() throws Exception {
        PlatformPageBootstrapService bootstrapService = mock(PlatformPageBootstrapService.class);
        PlatformModuleRuntimeContextService runtimeContextService = mock(PlatformModuleRuntimeContextService.class);
        ActiveTenantVerifier activeTenantVerifier = mock(ActiveTenantVerifier.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformPageBootstrapWebController(
                bootstrapService, runtimeContextService, activeTenantVerifier)).build();
        PlatformPageBootstrap bootstrap = new PlatformPageBootstrap(
                new PlatformPageEntryContext("menu-1", "mr.knowledge_file", MenuPageMode.LIST,
                        null, null, null),
                PlatformUiClientType.WEB,
                new PlatformResolvedPageConfig(List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
        );
        when(bootstrapService.bootstrapByMenu("menu-1", PlatformUiClientType.WEB)).thenReturn(bootstrap);
        when(runtimeContextService.context("mr.knowledge_file")).thenReturn(new PlatformModuleRuntimeContext(
                "mr.knowledge_file", "知识库管理", ModuleKind.STATIC, ModuleEntryType.ROUTE, null, null,
                "knowledgeFile", Set.of(EntityCapability.CRUD), List.of(), Set.of("crud"), List.of(), null));

        try (TenantContext.Scope ignored = TenantContext.system("system menu bootstrap")) {
            mvc.perform(get("/platform.menu/menu-1/entry"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entry.moduleAlias").value("mr.knowledge_file"));
        }

        verify(activeTenantVerifier, never()).verifyActiveTenant(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRecordOnlySuccessfulMenuBootstrapWithStablePageKey() throws Exception {
        PlatformPageBootstrapService bootstrapService = mock(PlatformPageBootstrapService.class);
        PlatformModuleRuntimeContextService runtimeContextService = mock(PlatformModuleRuntimeContextService.class);
        ActiveTenantVerifier activeTenantVerifier = mock(ActiveTenantVerifier.class);
        RecordingPublisher publisher = new RecordingPublisher();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformPageBootstrapWebController(
                bootstrapService, runtimeContextService, activeTenantVerifier,
                new BusinessLogPageAccessRecorder(publisher))).build();
        PlatformPageBootstrap bootstrap = new PlatformPageBootstrap(
                new PlatformPageEntryContext("menu-1", "iam.organization", MenuPageMode.LIST,
                        "ui-config-1", null, null), PlatformUiClientType.WEB,
                new PlatformResolvedPageConfig(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(bootstrapService.bootstrapByMenu("menu-1", PlatformUiClientType.WEB)).thenReturn(bootstrap);
        when(runtimeContextService.context("iam.organization")).thenReturn(new PlatformModuleRuntimeContext(
                "iam.organization", "组织管理", ModuleKind.STATIC, ModuleEntryType.ROUTE, null, null,
                "organization", Set.of(EntityCapability.CRUD), List.of(), Set.of("crud"), List.of(), null));

        try (TenantContext.Scope tenant = TenantContext.use("tenant-a");
             CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "alice", "tenant-a"));
             RequestTraceContext.Scope trace = RequestTraceContext.use("trace-page")) {
            mvc.perform(get("/platform.menu/menu-1/entry")).andExpect(status().isOk());
        }

        assertThat(publisher.events).singleElement().isInstanceOfSatisfying(PageAccessLogEvent.class, logged -> {
            assertThat(logged.context().traceId()).isEqualTo("trace-page");
            assertThat(logged.context().tenantId()).isEqualTo("tenant-a");
            assertThat(logged.context().operatorId()).isEqualTo("user-1");
            assertThat(logged.context().moduleAlias()).isEqualTo("iam.organization");
            assertThat(logged.details().pageKey()).isEqualTo("iam.organization:LIST");
            assertThat(logged.details().pageId()).isNull();
            assertThat(logged.details().menuId()).isEqualTo("menu-1");
        });
    }

    private static final class RecordingPublisher implements BusinessLogPublisher {
        private final java.util.List<BusinessLogEvent> events = new ArrayList<>();
        @Override public BusinessLogWriteResult publish(BusinessLogEvent event) {
            events.add(event);
            return new BusinessLogWriteResult(event.eventId(), BusinessLogWriteResult.Status.APPENDED);
        }
        @Override public java.util.List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events) {
            return events.stream().map(this::publish).toList();
        }
    }

    private PlatformModuleRuntimeAction action(String actionCode, boolean authorized) {
        return new PlatformModuleRuntimeAction(actionCode, actionCode, actionCode, null, null, null,
                false, false, null, null, null, authorized, authorized ? null : "denied");
    }
}
