package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
                        new PlatformActionBlock("organization-list", "action", null, "create", null, "toolbar"),
                        new PlatformActionBlock("organization-list", "action", null, "delete", null, "toolbar")
                ), List.of())
        );
        when(bootstrapService.bootstrapByMenu("menu-1", PlatformUiClientType.WEB)).thenReturn(bootstrap);
        when(runtimeContextService.context("iam.organization")).thenReturn(new PlatformModuleRuntimeContext(
                "iam.organization", "组织管理", ModuleKind.STATIC, ModuleEntryType.ROUTE, null, null,
                "organization", Set.of(EntityCapability.CRUD), Set.of("crud"), List.of(
                        action("create", true), action("delete", false))));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(get("/platform.menu/menu-1/entry"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entry.moduleAlias").value("iam.organization"))
                    .andExpect(jsonPath("$.mainEntityAlias").value("organization"))
                    .andExpect(jsonPath("$.resolvedConfig.actionBlocks.length()").value(1))
                    .andExpect(jsonPath("$.resolvedConfig.actionBlocks[0].actionCode").value("create"))
                    .andExpect(jsonPath("$.openApiPath").value("/iam.organization/openapi"));
        }

        verify(activeTenantVerifier).verifyActiveTenant("tenant-a");
    }

    private PlatformModuleRuntimeAction action(String actionCode, boolean authorized) {
        return new PlatformModuleRuntimeAction(actionCode, actionCode, actionCode, null, null, null,
                false, false, null, null, null, authorized, authorized ? null : "denied");
    }
}
