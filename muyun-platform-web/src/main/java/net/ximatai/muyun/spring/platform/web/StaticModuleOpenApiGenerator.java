package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.openapi.PlatformApiDocument;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformPermissionCode;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Generates static-module API documents from mappings accepted by Spring MVC. */
@Component
public class StaticModuleOpenApiGenerator {
    private static final List<String> DEFAULT_ERRORS = List.of(
            PlatformErrorCodes.VALIDATION_FAILED,
            PlatformErrorCodes.CONFLICT_VERSION,
            PlatformErrorCodes.RESOURCE_NOT_FOUND,
            PlatformErrorCodes.INTERNAL_ERROR
    );
    private final StaticModuleDefinitionCatalog moduleCatalog;
    private final RegisteredWebEndpointCatalog endpointCatalog;
    private final ActionEndpointContextResolver contextResolver;
    private final ActionExecutionPolicyService authorizationService;
    private final StaticModuleOpenApiSchemaFactory schemaFactory = new StaticModuleOpenApiSchemaFactory();

    public StaticModuleOpenApiGenerator(StaticModuleDefinitionCatalog moduleCatalog,
                                        RegisteredWebEndpointCatalog endpointCatalog) {
        this(moduleCatalog, endpointCatalog, new ActionEndpointContextResolver(), new AllowAllActionExecutionPolicyService());
    }

    @Autowired
    public StaticModuleOpenApiGenerator(StaticModuleDefinitionCatalog moduleCatalog,
                                        RegisteredWebEndpointCatalog endpointCatalog,
                                        ActionEndpointContextResolver contextResolver,
                                        ActionExecutionPolicyService authorizationService) {
        this.moduleCatalog = moduleCatalog;
        this.endpointCatalog = endpointCatalog;
        this.contextResolver = contextResolver;
        this.authorizationService = authorizationService;
    }

    public PlatformApiDocument generate(String moduleAlias) {
        StaticModuleDefinition module = moduleCatalog.find(moduleAlias)
                .orElseThrow(() -> new IllegalArgumentException("unknown static module: " + moduleAlias));
        String basePath = "/" + module.moduleAlias();
        List<PlatformApiDocument.Operation> operations = endpointCatalog.endpoints().stream()
                .filter(endpoint -> module.moduleAlias().equals(endpoint.definition().moduleAlias()))
                .filter(endpoint -> !isOpenApiEndpoint(endpoint.definition()))
                .filter(endpoint -> authorized(endpoint.definition()))
                .map(endpoint -> operation(endpoint, schemaFactory.mainSchemaName(module)))
                .toList();
        return new StaticOpenApiDocument(module.moduleAlias(), module.title(), basePath, operations,
                schemaFactory.schemas(module), errors());
    }

    private boolean authorized(net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint endpoint) {
        try {
            authorizationService.authorize(contextResolver.resolve(endpoint));
            return true;
        } catch (AuthenticationRequiredException | PlatformAccessDeniedException ignored) {
            return false;
        }
    }

    private boolean isOpenApiEndpoint(net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint endpoint) {
        return "openApi".equals(endpoint.operationCode()) && endpoint.path().endsWith("/openapi");
    }

    private PlatformApiDocument.Operation operation(RegisteredWebEndpoint endpoint, String mainSchemaName) {
        var definition = endpoint.definition();
        String actionCode = definition.action().code();
        String requestSchema = requestSchema(definition.action(), mainSchemaName);
        String responseSchema = responseSchema(definition.action(), mainSchemaName);
        return new PlatformApiDocument.Operation(definition.method().name(), definition.path(), definition.endpointId(),
                definition.operationCode(), requestSchema, responseSchema, actionCode,
                PlatformPermissionCode.action(definition.moduleAlias(),
                        PlatformAction.permissionActionCodeOf(actionCode)), DEFAULT_ERRORS,
                definition.action() == PlatformAction.CREATE ? 201 : 200, null,
                definition.action() == PlatformAction.QUERY ? Map.of() : null);
    }

    private String requestSchema(PlatformAction action, String mainSchemaName) {
        if (action == PlatformAction.QUERY) return "WebQueryRequest";
        if (action == PlatformAction.DELETE) return "RecordActionWebRequest";
        return action == PlatformAction.CREATE || action == PlatformAction.UPDATE ? mainSchemaName : null;
    }

    private String responseSchema(PlatformAction action, String mainSchemaName) {
        if (action == PlatformAction.QUERY) return mainSchemaName == null ? null : mainSchemaName + "PageResponse";
        if (action == PlatformAction.VIEW || action == PlatformAction.CREATE || action == PlatformAction.UPDATE) return mainSchemaName;
        if (action == PlatformAction.DELETE || action == PlatformAction.ENABLE || action == PlatformAction.DISABLE
                || action == PlatformAction.SORT) return "integer";
        return null;
    }

    private Map<String, PlatformApiDocument.ErrorResponse> errors() {
        return Map.of(
                PlatformErrorCodes.VALIDATION_FAILED, new PlatformApiDocument.ErrorResponse(
                        PlatformErrorCodes.VALIDATION_FAILED, 400, "PlatformWebError"),
                PlatformErrorCodes.RESOURCE_NOT_FOUND, new PlatformApiDocument.ErrorResponse(
                        PlatformErrorCodes.RESOURCE_NOT_FOUND, 404, "PlatformWebError"),
                PlatformErrorCodes.CONFLICT_VERSION, new PlatformApiDocument.ErrorResponse(
                        PlatformErrorCodes.CONFLICT_VERSION, 409, "PlatformWebError"),
                PlatformErrorCodes.INTERNAL_ERROR, new PlatformApiDocument.ErrorResponse(
                        PlatformErrorCodes.INTERNAL_ERROR, 500, "PlatformWebError")
        );
    }

}
