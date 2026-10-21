package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinition;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinitionService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinitionStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowPublishFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowPublishStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowVersion;
import net.ximatai.muyun.spring.platform.workflow.WorkflowVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerMapping;

import net.ximatai.muyun.spring.web.RecordActionWebRequest;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowConfigurationWebControllerTest {
    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shouldBindWorkflowDefinitionModuleFromPathAndForceDraftOnInsert(boolean withAlias) throws Exception {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        WorkflowPublishFacade publishFacade = mock(WorkflowPublishFacade.class);
        WorkflowDefinitionWebController controller = new WorkflowDefinitionWebController(moduleService,
                publishFacade,
                org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService.class));
        ReflectionTestUtils.setField(controller, "service", definitionService);
        when(moduleService.resolveVisibleModule("sales.contract")).thenReturn(module("sales.contract"));
        WorkflowDefinition inserted = definition("def-1", "sales.contract", WorkflowDefinitionStatus.DRAFT);
        when(definitionService.insert(any(WorkflowDefinition.class))).thenReturn("def-1");
        when(definitionService.select("def-1")).thenReturn(inserted);

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/platform.module/sales.contract/workflow-definitions/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationAlias":"other",
                                  "moduleAlias":"other.module",
                                  %s
                                  "title":"Approval",
                                  "definitionStatus":"PUBLISHED",
                                  "currentVersionNo":3
                                }
                                """.formatted(withAlias ? "\"alias\":\"approval\"," : "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationAlias").value("sales"))
                .andExpect(jsonPath("$.moduleAlias").value("sales.contract"));

        ArgumentCaptor<WorkflowDefinition> captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(definitionService).insert(captor.capture());
        assertThat(captor.getValue().getApplicationAlias()).isEqualTo("sales");
        assertThat(captor.getValue().getModuleAlias()).isEqualTo("sales.contract");
        assertThat(captor.getValue().getDefinitionStatus()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        assertThat(captor.getValue().getAlias()).isEqualTo(withAlias ? "approval" : null);
        assertThat(captor.getValue().getCurrentVersionNo()).isNull();
    }

    @Test
    void shouldRejectWorkflowDefinitionInsertWhenPathModuleDoesNotExist() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        WorkflowDefinitionWebController controller = new WorkflowDefinitionWebController(moduleService,
                mock(WorkflowPublishFacade.class),
                org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService.class));
        ReflectionTestUtils.setField(controller, "service", definitionService);
        when(moduleService.resolveVisibleModule("sales.ghost")).thenReturn(null);

        assertThatThrownBy(() -> controller.insert(
                requestVars("sales.ghost", "def-1"),
                definition(null, "sales.ghost", WorkflowDefinitionStatus.DRAFT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform module not found");
    }

    @Test
    void shouldRejectEditingPublishedWorkflowDefinitionThroughCrudPath() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionWebController controller = new WorkflowDefinitionWebController(mock(PlatformModuleService.class),
                mock(WorkflowPublishFacade.class),
                org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService.class));
        ReflectionTestUtils.setField(controller, "service", definitionService);
        when(definitionService.select("def-1")).thenReturn(
                definition("def-1", "sales.contract", WorkflowDefinitionStatus.PUBLISHED));

        assertThatThrownBy(() -> controller.update(
                requestVars("sales.contract", "def-1"),
                "def-1",
                definition("def-1", "sales.contract", WorkflowDefinitionStatus.DRAFT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can only edit draft definitions");
    }

    @Test
    void shouldBindWorkflowVersionDefinitionAndForceDraftOnInsert() throws Exception {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowVersionService versionService = mock(WorkflowVersionService.class);
        WorkflowVersionWebController controller = new WorkflowVersionWebController(definitionService);
        ReflectionTestUtils.setField(controller, "service", versionService);
        when(definitionService.select("def-1")).thenReturn(
                definition("def-1", "sales.contract", WorkflowDefinitionStatus.DRAFT));
        WorkflowVersion inserted = version("ver-1", "def-1", 1, WorkflowPublishStatus.DRAFT);
        when(versionService.insert(any(WorkflowVersion.class))).thenReturn("ver-1");
        when(versionService.select("ver-1")).thenReturn(inserted);

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/platform.module/sales.contract/workflow-definitions/def-1/versions/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionId":"other-def",
                                  "versionNo":1,
                                  "publishStatus":"PUBLISHED",
                                  "publishedBy":"other"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definitionId").value("def-1"))
                .andExpect(jsonPath("$.publishStatus").value("DRAFT"));

        ArgumentCaptor<WorkflowVersion> captor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(versionService).insert(captor.capture());
        assertThat(captor.getValue().getDefinitionId()).isEqualTo("def-1");
        assertThat(captor.getValue().getPublishStatus()).isEqualTo(WorkflowPublishStatus.DRAFT);
        assertThat(captor.getValue().getPublishedBy()).isNull();
    }

    @Test
    void shouldPublishWorkflowVersionThroughPublishFacadeWithCurrentUser() throws Exception {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        WorkflowPublishFacade publishFacade = mock(WorkflowPublishFacade.class);
        WorkflowDefinitionWebController controller = new WorkflowDefinitionWebController(moduleService,
                publishFacade,
                org.mockito.Mockito.mock(net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService.class));
        ReflectionTestUtils.setField(controller, "service", definitionService);
        when(definitionService.select("def-1")).thenReturn(
                definition("def-1", "sales.contract", WorkflowDefinitionStatus.DRAFT));
        when(publishFacade.publish("def-1", "ver-1", 3, 2, "user-1"))
                .thenReturn(version("ver-1", "def-1", 1, WorkflowPublishStatus.PUBLISHED));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant-a"))))
                .build();
        mvc.perform(post("/platform.module/sales.contract/workflow-definitions/def-1/versions/ver-1/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionVersion\":3,\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishStatus").value("PUBLISHED"));

        verify(publishFacade).publish("def-1", "ver-1", 3, 2, "user-1");
    }

    @Test
    void formulaAuthoringUsesVisibleModuleScopeAndTheSharedEngineCatalog() throws Exception {
        var modules = mock(PlatformModuleService.class);
        var designs = new net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService(
                mock(WorkflowDefinitionService.class), mock(WorkflowVersionService.class),
                mock(net.ximatai.muyun.spring.platform.workflow.WorkflowNodeDefinitionDao.class),
                mock(net.ximatai.muyun.spring.platform.workflow.WorkflowLinkDefinitionDao.class),
                new net.ximatai.muyun.spring.platform.workflow.WorkflowDesignCompiler(
                        new net.ximatai.muyun.spring.platform.workflow.WorkflowConditionService((module, record) -> {
                            throw new AssertionError("syntax validation must not read business records");
                        })), mock(net.ximatai.muyun.spring.platform.workflow.WorkflowBusinessTaskResolver.class));
        var controller = new WorkflowDefinitionWebController(modules, mock(WorkflowPublishFacade.class), designs);
        when(modules.resolveVisibleModule("sales.contract")).thenReturn(module("sales.contract"));
        var request = requestVars("sales.contract", "unused");
        assertThat(controller.formulaFunctions(request)).extracting(net.ximatai.muyun.spring.platform.metadata.BusinessRuleFunction::name)
                .contains("PRESENT", "ISNULL", "IN").doesNotContain("SUM", "COUNT");
        assertThat(controller.validateCondition(request, new WorkflowDefinitionWebController.ConditionValidationRequest("{amount} > 100")))
                .containsEntry("valid", true);
        assertThatThrownBy(() -> controller.validateCondition(request,
                new WorkflowDefinitionWebController.ConditionValidationRequest("{amount} = 100")))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class);
        assertThatThrownBy(() -> controller.validateCondition(request,
                new WorkflowDefinitionWebController.ConditionValidationRequest("{amount} >")))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class);
        assertThatThrownBy(() -> controller.formulaFunctions(requestVars("sales.hidden", "unused")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldEnableThroughVersionedFacadeOnlyInsideTheOwningModule() throws Exception {
        WorkflowDefinitionService definitions = mock(WorkflowDefinitionService.class);
        WorkflowPublishFacade publish = mock(WorkflowPublishFacade.class);
        var controller = new WorkflowDefinitionWebController(mock(PlatformModuleService.class), publish,
                mock(net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService.class));
        ReflectionTestUtils.setField(controller, "service", definitions);
        when(definitions.select("def-1")).thenReturn(definition("def-1", "sales.contract", WorkflowDefinitionStatus.DISABLED));
        when(publish.enable("def-1", 3)).thenReturn(definition("def-1", "sales.contract", WorkflowDefinitionStatus.PUBLISHED));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform.module/sales.contract/workflow-definitions/def-1/enable")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.definitionStatus").value("PUBLISHED"));
        verify(publish).enable("def-1", 3);
        assertThatThrownBy(() -> controller.enableDefinition(requestVars("sales.other", "def-1"), "def-1",
                new RecordActionWebRequest(3)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(publish, org.mockito.Mockito.times(1)).enable("def-1", 3);
    }

    private WorkflowDefinition definition(String id, String moduleAlias, WorkflowDefinitionStatus status) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(id);
        definition.setApplicationAlias(moduleAlias.substring(0, moduleAlias.indexOf('.')));
        definition.setModuleAlias(moduleAlias);
        definition.setAlias("approval");
        definition.setTitle("Approval");
        definition.setDefinitionStatus(status);
        return definition;
    }

    private PlatformModule module(String alias) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setTitle(alias);
        return module;
    }

    private WorkflowVersion version(String id, String definitionId, int versionNo, WorkflowPublishStatus status) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(id);
        version.setDefinitionId(definitionId);
        version.setVersionNo(versionNo);
        version.setPublishStatus(status);
        return version;
    }

    private MockHttpServletRequest requestVars(String moduleAlias, String definitionId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("moduleAlias", moduleAlias, "definitionId", definitionId));
        return request;
    }
}
