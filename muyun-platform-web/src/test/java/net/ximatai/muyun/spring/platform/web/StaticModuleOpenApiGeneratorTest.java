package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformPermissionCode;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.openapi.OpenApi31Projector;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaticModuleOpenApiGeneratorTest {
    private StaticModuleOpenApiGenerator generator;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldGenerateDocumentFromAcceptedStaticEndpointCatalog() {
        RegisteredWebEndpointCatalog endpointCatalog = new RegisteredWebEndpointCatalog();
        register(endpointCatalog, endpoint("education.teacher.query", "education.teacher", "query",
                PlatformAction.QUERY, RequestMethod.POST, "/education.teacher/query"));
        register(endpointCatalog, endpoint("education.teacher.enable", "education.teacher", "enable",
                PlatformAction.ENABLE, RequestMethod.POST, "/education.teacher/enable/{id}"));
        register(endpointCatalog, endpoint("education.student.query", "education.student", "query",
                PlatformAction.QUERY, RequestMethod.POST, "/education.student/query"));
        generator = new StaticModuleOpenApiGenerator(
                new StaticModuleDefinitionCatalog(List.of(
                        StaticModuleDefinition.builder("education", "education.teacher", "教师")
                                .entities(List.of(new EntityDefinition("teacher", "teacher", "教师",
                                        List.of(FieldDefinition.titleField()))))
                                .build())),
                endpointCatalog);

        var document = generator.generate("education.teacher");

        assertThat(document.moduleAlias()).isEqualTo("education.teacher");
        assertThat(document.title()).isEqualTo("教师");
        assertThat(document.basePath()).isEqualTo("/education.teacher");
        assertThat(document.operations())
                .extracting(operation -> operation.path())
                .containsExactly("/education.teacher/query", "/education.teacher/enable/{id}")
                .doesNotContain("/education.teacher/openapi");
        assertThat(document.operations().getFirst().permissionCode()).isEqualTo(
                PlatformPermissionCode.action("education.teacher",
                        PlatformAction.permissionActionCodeOf(PlatformAction.QUERY.code())));
        assertThat(document.operations().getFirst().errorCodes())
                .contains(PlatformErrorCodes.VALIDATION_FAILED, PlatformErrorCodes.INTERNAL_ERROR);
        assertThat(document.errors()).containsKeys(PlatformErrorCodes.VALIDATION_FAILED,
                PlatformErrorCodes.CONFLICT_VERSION, PlatformErrorCodes.RESOURCE_NOT_FOUND,
                PlatformErrorCodes.INTERNAL_ERROR);
    }

    @Test
    void shouldHideStaticActionPathsDeniedToCurrentCaller() {
        RegisteredWebEndpointCatalog endpointCatalog = new RegisteredWebEndpointCatalog();
        register(endpointCatalog, endpoint("education.teacher.query", "education.teacher", "query",
                PlatformAction.QUERY, RequestMethod.POST, "/education.teacher/query"));
        register(endpointCatalog, endpoint("education.teacher.enable", "education.teacher", "enable",
                PlatformAction.ENABLE, RequestMethod.POST, "/education.teacher/enable/{id}"));
        ActionExecutionPolicyService authorizationService = mock(ActionExecutionPolicyService.class);
        doThrow(new PlatformAccessDeniedException("action permission denied"))
                .when(authorizationService)
                .authorize(org.mockito.ArgumentMatchers.argThat(context -> context != null
                        && "enable".equals(context.actionCode())));
        generator = new StaticModuleOpenApiGenerator(
                new StaticModuleDefinitionCatalog(List.of(
                        StaticModuleDefinition.builder("education", "education.teacher", "教师").build())),
                endpointCatalog, new ActionEndpointContextResolver(), authorizationService);

        var document = generator.generate("education.teacher");

        assertThat(document.operations()).extracting(operation -> operation.path())
                .contains("/education.teacher/query")
                .doesNotContain("/education.teacher/enable/{id}");
    }

    @Test
    void shouldHideDisabledStaticActionPathsFromOpenApi() {
        RegisteredWebEndpointCatalog endpointCatalog = new RegisteredWebEndpointCatalog();
        register(endpointCatalog, endpoint("education.teacher.query", "education.teacher", "query",
                PlatformAction.QUERY, RequestMethod.POST, "/education.teacher/query"));
        register(endpointCatalog, endpoint("education.teacher.enable", "education.teacher", "enable",
                PlatformAction.ENABLE, RequestMethod.POST, "/education.teacher/enable/{id}"));
        PlatformModuleActionService actionService = mock(PlatformModuleActionService.class);
        PlatformModuleAction disabledEnable = new PlatformModuleAction();
        disabledEnable.setModuleAlias("education.teacher");
        disabledEnable.setActionCode("enable");
        disabledEnable.setEnabled(Boolean.FALSE);
        when(actionService.findByModuleAliasAndActionCode("education.teacher", "enable"))
                .thenReturn(disabledEnable);
        generator = new StaticModuleOpenApiGenerator(
                new StaticModuleDefinitionCatalog(List.of(
                        StaticModuleDefinition.builder("education", "education.teacher", "教师").build())),
                endpointCatalog, new ActionEndpointContextResolver(actionService),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService());

        var document = generator.generate("education.teacher");

        assertThat(document.operations()).extracting(operation -> operation.path())
                .contains("/education.teacher/query")
                .doesNotContain("/education.teacher/enable/{id}");
    }

    @Test
    void shouldDescribeStaticCrudWireContracts() {
        RegisteredWebEndpointCatalog endpointCatalog = new RegisteredWebEndpointCatalog();
        register(endpointCatalog, endpoint("education.teacher.query", "education.teacher", "query",
                PlatformAction.QUERY, RequestMethod.POST, "/education.teacher/query"));
        register(endpointCatalog, endpoint("education.teacher.insert", "education.teacher", "insert",
                PlatformAction.CREATE, RequestMethod.POST, "/education.teacher/insert"));
        register(endpointCatalog, endpoint("education.teacher.delete", "education.teacher", "delete",
                PlatformAction.DELETE, RequestMethod.POST, "/education.teacher/delete/{id}"));
        generator = new StaticModuleOpenApiGenerator(
                new StaticModuleDefinitionCatalog(List.of(
                        StaticModuleDefinition.builder("education", "education.teacher", "教师")
                                .entities(List.of(new EntityDefinition("teacher", "teacher", "教师",
                                        List.of(FieldDefinition.titleField()))))
                                .build())),
                endpointCatalog);

        var document = generator.generate("education.teacher");

        assertThat(document.schemas().get("WebQueryRequest").properties().get("page").type())
                .isEqualTo("WebPageRequest");
        assertThat(document.schemas().get("WebPageRequest").properties()).containsKeys("pageNum", "pageSize");
        assertThat(document.schemas().get("TeacherPageResponse").properties())
                .containsKeys("records", "total", "pageNum", "pageSize", "pages", "totalKnown", "navigation");
        assertThat(document.schemas().get("Teacher").properties())
                .containsKeys("id", "tenantId", "version", "deleted", "createdAt", "updatedAt");
        assertThat(document.schemas().get("Teacher").properties().get("version").optionSource())
                .contains("Optimistic lock");
        assertThat(document.operations()).filteredOn(operation -> PlatformAction.CREATE.code().equals(operation.actionCode()))
                .singleElement().extracting(operation -> operation.successStatus()).isEqualTo(201);
        assertThat(document.operations()).filteredOn(operation -> PlatformAction.CREATE.code().equals(operation.actionCode()))
                .singleElement().extracting(operation -> operation.requestSchema()).isEqualTo("Teacher");
        assertThat(document.operations()).filteredOn(operation -> PlatformAction.DELETE.code().equals(operation.actionCode()))
                .singleElement().extracting(operation -> operation.requestSchema()).isEqualTo("RecordActionWebRequest");
        assertThat(document.operations()).filteredOn(operation -> PlatformAction.QUERY.code().equals(operation.actionCode()))
                .singleElement().extracting(operation -> operation.requestExample()).isEqualTo(Map.of());
        @SuppressWarnings("unchecked")
        var paths = (java.util.Map<String, Object>) OpenApi31Projector.project(document).get("paths");
        @SuppressWarnings("unchecked")
        var insert = (java.util.Map<String, Object>) paths.get("/education.teacher/insert");
        assertThat(insert).containsKey("post");
    }

    @Test
    void shouldExposeOpenApiFromAnnotationManagedEndpoint() {
        generator = new StaticModuleOpenApiGenerator(
                new StaticModuleDefinitionCatalog(List.of(
                        StaticModuleDefinition.builder("education", "education.teacher", "教师").build())),
                new RegisteredWebEndpointCatalog());
        StaticModuleOpenApiEndpoint endpoint = new StaticModuleOpenApiEndpoint(generator);
        endpoint.register("education.teacher", "/education.teacher/openapi");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/education.teacher/openapi");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            var document = endpoint.openApi(request);
            assertThat(document).containsEntry("openapi", "3.1.1")
                    .containsEntry("x-muyun-module-alias", "education.teacher")
                    .containsEntry("x-muyun-module-base-path", "/education.teacher");
        }
    }

    @Test
    void shouldRejectNonModuleOpenApiPath() {
        generator = new StaticModuleOpenApiGenerator(
                new StaticModuleDefinitionCatalog(List.of(
                        StaticModuleDefinition.builder("education", "education.notice", "通知").build())),
                new RegisteredWebEndpointCatalog());
        StaticModuleOpenApiEndpoint endpoint = new StaticModuleOpenApiEndpoint(generator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/education.notice/other");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> endpoint.openApi(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unknown static module OpenAPI mapping");
        }
    }

    @Test
    void shouldResolveCustomOpenApiPathFromRegisteredModuleMapping() {
        generator = new StaticModuleOpenApiGenerator(
                new StaticModuleDefinitionCatalog(List.of(
                        StaticModuleDefinition.builder("iam", "iam.organization", "组织").build())),
                new RegisteredWebEndpointCatalog());
        StaticModuleOpenApiEndpoint endpoint = new StaticModuleOpenApiEndpoint(generator);
        endpoint.register("iam.organization", "/organizations/openapi");

        Map<String, Object> document = endpoint.openApi(new MockHttpServletRequest("GET", "/organizations/openapi"));

        assertThat(document).containsEntry("x-muyun-module-alias", "iam.organization");
    }

    private void register(RegisteredWebEndpointCatalog catalog, ResolvedWebEndpoint definition) {
        RegisteredWebEndpoint endpoint = mock(RegisteredWebEndpoint.class);
        when(endpoint.definition()).thenReturn(definition);
        catalog.register(endpoint);
    }

    private ResolvedWebEndpoint endpoint(String endpointId,
                                         String moduleAlias,
                                         String operationCode,
                                         PlatformAction action,
                                         RequestMethod method,
                                         String path) {
        return new ResolvedWebEndpoint(endpointId, moduleAlias, "standard", operationCode, action,
                method, path, ResolvedWebEndpoint.Source.STATIC_ABILITY);
    }

}
