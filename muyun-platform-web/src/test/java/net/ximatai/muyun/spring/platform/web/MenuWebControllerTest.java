package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.web.MuYunSpringJacksonConfiguration;
import net.ximatai.muyun.spring.iam.web.security.BearerTokenCurrentUserProvider;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.platform.web.endpoint.StaticAbilityWebEndpointRegistrar;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;
import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.menu.MenuScopeType;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MenuWebControllerTest {
    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void shouldExposeCurrentUserVisibleMenuTreeWithoutSchemeInput() throws Exception {
        MenuService menuService = mock(MenuService.class);
        MenuWebController controller = new MenuWebController(menuService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(codeTitleEnumConverter())
                .build();
        Menu root = menu("root-1", "scheme-1", "业务中心", null);
        Menu child = menu("menu-1", "scheme-1", "客户", "crm.customer");
        when(menuService.currentUserVisibleRootMenus()).thenReturn(List.of(root));
        when(menuService.visibleChildren("scheme-1", "root-1")).thenReturn(List.of(child));
        when(menuService.visibleChildren("scheme-1", "menu-1")).thenReturn(List.of());
        when(menuService.navigationEntryType(child)).thenReturn(ModuleEntryType.MODULE);

        mvc.perform(get("/platform.menu/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("root-1"))
                .andExpect(jsonPath("$.records[0].children[0].record.openMode").value("tab"))
                .andExpect(jsonPath("$.records[0].children[0].record.moduleAlias").value("crm.customer"))
                .andExpect(jsonPath("$.records[0].children[0].record.entryType").value("module"));
    }

    @Test
    void shouldResolveCurrentUserFromBearerTokenBeforeReturningMineMenuTree() throws Exception {
        MenuService menuService = mock(MenuService.class);
        UserSessionService sessionService = mock(UserSessionService.class);
        MenuWebController controller = new MenuWebController(menuService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(codeTitleEnumConverter())
                .addFilters(new CurrentUserWebFilter(new BearerTokenCurrentUserProvider(sessionService)))
                .build();
        CurrentUser currentUser = CurrentUser.tenantUser("user-1", "alice", "tenant-a", "dept-1");
        Menu root = menu("root-1", "scheme-1", "业务中心", null);
        Menu child = menu("menu-1", "scheme-1", "客户", "crm.customer");
        when(sessionService.currentUser("token-1")).thenReturn(Optional.of(currentUser));
        when(menuService.currentUserVisibleRootMenus()).thenAnswer(invocation -> {
            assertThat(CurrentUserContext.currentUser()).contains(currentUser);
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            return List.of(root);
        });
        when(menuService.visibleChildren("scheme-1", "root-1")).thenAnswer(invocation -> {
            assertThat(CurrentUserContext.currentUser()).contains(currentUser);
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            return List.of(child);
        });
        when(menuService.visibleChildren("scheme-1", "menu-1")).thenReturn(List.of());
        when(menuService.navigationEntryType(child)).thenReturn(ModuleEntryType.MODULE);

        mvc.perform(get("/platform.menu/mine")
                        .header("Authorization", "Bearer token-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("root-1"))
                .andExpect(jsonPath("$.records[0].children[0].record.openMode").value("tab"))
                .andExpect(jsonPath("$.records[0].children[0].record.moduleAlias").value("crm.customer"))
                .andExpect(jsonPath("$.records[0].children[0].record.entryType").value("module"));

        verify(sessionService).currentUser("token-1");
    }

    @Test
    void shouldReturnConfigurationErrorWhenCurrentUserHasNoMenuScheme() throws Exception {
        MenuService menuService = mock(MenuService.class);
        MenuWebController controller = new MenuWebController(menuService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(codeTitleEnumConverter())
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();
        when(menuService.currentUserVisibleRootMenus())
                .thenThrow(new PlatformConfigurationException("menu scheme is not configured for current user"));

        mvc.perform(get("/platform.menu/mine"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.CONFIG_MISSING))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("menu scheme is not configured for current user"));
    }

    @Test
    void shouldExposeMenuSchemeMaintenance() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MenuSchemeService schemeService = mock(MenuSchemeService.class);
        MenuSchemeWebController controller = new MenuSchemeWebController();
        ReflectionTestUtils.setField(controller, "service", schemeService);
        MenuScheme scheme = scheme("scheme-1", "default");
        when(schemeService.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(scheme), 1, PageRequest.of(1, 20)));
        when(schemeService.insert(any(MenuScheme.class))).thenReturn("scheme-1");
        when(schemeService.select("scheme-1")).thenReturn(scheme);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(codeTitleEnumConverter())
                .build();
        mvc.perform(post("/platform.menu_scheme/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":[{"fieldName":"alias","values":["default"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("scheme-1"))
                .andExpect(jsonPath("$.records[0].alias").value("default"));
        mvc.perform(post("/platform.menu_scheme/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alias":"default","scopeType":"tenant","title":"Default"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("scheme-1"));

        ArgumentCaptor<MenuScheme> captor = ArgumentCaptor.forClass(MenuScheme.class);
        verify(schemeService).insert(captor.capture());
        assertThat(captor.getValue().getAlias()).isEqualTo("default");
    }

    @Test
    void shouldExposeMenuMaintenanceAndTreeScopedByNavigatorScheme() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MenuService menuService = mock(MenuService.class);
        MenuManagementWebController controller = new MenuManagementWebController();
        ReflectionTestUtils.setField(controller, "service", menuService);
        Menu root = menu("root-1", "scheme-1", "业务中心", null);
        Menu child = menu("menu-1", "scheme-1", "客户", "crm.customer");
        Menu inserted = menu("menu-2", "scheme-1", "订单", "crm.order");
        when(menuService.children(any(Criteria.class), org.mockito.ArgumentMatchers.eq("root")))
                .thenReturn(List.of(root));
        when(menuService.children(any(Criteria.class), org.mockito.ArgumentMatchers.eq("root-1")))
                .thenReturn(List.of(child));
        when(menuService.selectInScope(any(Criteria.class), org.mockito.ArgumentMatchers.eq("root-1")))
                .thenReturn(root);
        when(menuService.children(any(Criteria.class), org.mockito.ArgumentMatchers.eq("menu-1")))
                .thenReturn(List.of());
        when(menuService.insert(any(Menu.class))).thenReturn("menu-2");
        when(menuService.select("menu-2")).thenReturn(inserted);

        MockMvc mvc = abilityAwareMvc(controller);
        mvc.perform(post("/platform.menu/tree/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalQueryValues":{"schemeId":"scheme-1"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("root-1"))
                .andExpect(jsonPath("$.records[0].children[0].record.id").value("menu-1"));
        mvc.perform(post("/platform.menu/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(PageContextScopePolicy.CONTEXT_HEADER, "{\"scheme\":\"scheme-1\"}")
                        .content("""
                                {"schemeId":"other-scheme","parentId":"root-1","title":"订单","moduleAlias":"crm.order"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schemeId").value("scheme-1"));

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        verify(menuService).insert(captor.capture());
        assertThat(captor.getValue().getSchemeId()).isEqualTo("scheme-1");
    }

    @Test
    void shouldReturnActionMessageWhenMenuMutationFailsWithBusinessException() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MenuService menuService = mock(MenuService.class);
        MenuManagementWebController controller = new MenuManagementWebController();
        ReflectionTestUtils.setField(controller, "service", menuService);
        when(menuService.insert(any(Menu.class))).thenThrow(new BusinessException(
                "platform.menu.open-mode-required",
                "Module entry menu requires openMode"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(codeTitleEnumConverter())
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(post("/platform.menu/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(PageContextScopePolicy.CONTEXT_HEADER, "{\"scheme\":\"scheme-1\"}")
                        .content("""
                                {"parentId":"root-1","title":"订单","moduleAlias":"crm.order"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("platform.menu.open-mode-required"))
                .andExpect(jsonPath("$.message").value("Module entry menu requires openMode"))
                .andExpect(jsonPath("$.actionMessage.code").value("platform.menu.open-mode-required"))
                .andExpect(jsonPath("$.actionMessage.text").value("Module entry menu requires openMode"))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"));
    }

    @Test
    void shouldRejectMenuReadsAndMutationsOutsideExplicitSchemeContext() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MenuService menuService = mock(MenuService.class);
        MenuManagementWebController controller = new MenuManagementWebController();
        ReflectionTestUtils.setField(controller, "service", menuService);
        Menu otherScheme = menu("menu-1", "scheme-2", "客户", "crm.customer");
        when(menuService.select("menu-1")).thenReturn(otherScheme);
        MockMvc mvc = abilityAwareMvc(controller);

        mvc.perform(get("/platform.menu/tree").param("schemeId", "scheme-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page navigator scope is required: scheme"));
        mvc.perform(post("/platform.menu/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("Page navigator scope is required: scheme"));
        mvc.perform(get("/platform.menu/view/menu-1")
                        .header(PageContextScopePolicy.CONTEXT_HEADER, "{\"scheme\":\"scheme-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Record does not belong to the current page scope: schemeId"));
        mvc.perform(post("/platform.menu/update/menu-1").contentType(MediaType.APPLICATION_JSON)
                        .header(PageContextScopePolicy.CONTEXT_HEADER, "{\"scheme\":\"scheme-1\"}")
                        .content("{\"title\":\"变更\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/platform.menu/delete/menu-1").contentType(MediaType.APPLICATION_JSON)
                        .header(PageContextScopePolicy.CONTEXT_HEADER, "{\"scheme\":\"scheme-1\"}")
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(menuService, never()).update(any(Menu.class));
        verify(menuService, never()).delete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private Menu menu(String id, String schemeId, String title, String moduleAlias) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setSchemeId(schemeId);
        menu.setTitle(title);
        menu.setModuleAlias(moduleAlias);
        if (moduleAlias != null && !moduleAlias.isBlank()) {
            menu.setOpenMode(MenuOpenMode.TAB);
        }
        menu.setEnabled(Boolean.TRUE);
        return menu;
    }

    private MenuScheme scheme(String id, String alias) {
        MenuScheme scheme = new MenuScheme();
        scheme.setId(id);
        scheme.setAlias(alias);
        scheme.setTitle(alias);
        scheme.setScopeType(MenuScopeType.TENANT);
        scheme.setEnabled(Boolean.TRUE);
        return scheme;
    }

    private MappingJackson2HttpMessageConverter codeTitleEnumConverter() {
        return new MappingJackson2HttpMessageConverter(codeTitleObjectMapper());
    }

    private MockMvc abilityAwareMvc(Object controller) {
        ObjectMapper objectMapper = codeTitleObjectMapper();
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomHandlerMapping(() -> new AbilityAwareHandlerMapping(objectMapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();
    }

    private ObjectMapper codeTitleObjectMapper() {
        return new ObjectMapper()
                .registerModule(new MuYunSpringJacksonConfiguration().codeTitleEnumJacksonModule());
    }

    private static final class AbilityAwareHandlerMapping extends RequestMappingHandlerMapping {
        private final ObjectMapper objectMapper;

        private AbilityAwareHandlerMapping(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void afterPropertiesSet() {
            super.afterPropertiesSet();
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            ObjectProvider<net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade> provider =
                    beanFactory.getBeanProvider(net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade.class);
            new StaticAbilityWebEndpointRegistrar(
                    obtainApplicationContext(),
                    this,
                    new RegisteredWebEndpointCatalog(),
                    provider,
                    objectMapper
            ).afterSingletonsInstantiated();
        }
    }
}
