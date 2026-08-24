package net.ximatai.muyun.spring.dynamic.openapi;

import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformPermissionCode;
import net.ximatai.muyun.spring.common.web.PlatformWebPathRules;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public class DynamicOpenApiGenerator {
    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";
    private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> DEFAULT_ERRORS = List.of(
            PlatformErrorCodes.VALIDATION_FAILED,
            "DYNAMIC_ACTION_FAILED",
            PlatformErrorCodes.CONFLICT_VERSION,
            PlatformErrorCodes.RESOURCE_NOT_FOUND,
            PlatformErrorCodes.CONFIG_MISSING,
            PlatformErrorCodes.INTERNAL_ERROR
    );

    public DynamicOpenApiDocument generate(DynamicModuleDescriptor descriptor) {
        return generate(descriptor, action -> true);
    }

    public DynamicOpenApiDocument generate(DynamicModuleDescriptor descriptor,
                                           Predicate<PlatformAction> standardActionVisible) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(standardActionVisible, "standardActionVisible must not be null");
        DynamicEntityDescriptor mainEntity = requireMainEntity(descriptor);
        String basePath = "/" + descriptor.moduleAlias();
        Map<String, DynamicOpenApiDocument.Schema> schemas = schemas(mainEntity);
        return new DynamicOpenApiDocument(
                descriptor.moduleAlias(),
                descriptor.title(),
                basePath,
                operations(descriptor, mainEntity, basePath, standardActionVisible),
                schemas,
                errors()
        );
    }

    private DynamicEntityDescriptor requireMainEntity(DynamicModuleDescriptor descriptor) {
        return descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(descriptor.mainEntityAlias()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dynamic OpenAPI main entity not found: "
                        + descriptor.moduleAlias() + "." + descriptor.mainEntityAlias()));
    }

    private List<DynamicOpenApiDocument.Operation> operations(DynamicModuleDescriptor descriptor,
                                                              DynamicEntityDescriptor mainEntity,
                                                              String basePath,
                                                              Predicate<PlatformAction> standardActionVisible) {
        List<DynamicOpenApiDocument.Operation> operations = new ArrayList<>();
        operations.add(getOperation(descriptor.moduleAlias(), basePath + "/describe", "describe" + upperModuleName(descriptor.moduleAlias()),
                "Describe " + descriptor.title(), null, "DynamicModuleDescriptor", null));
        if (standardActionVisible.test(PlatformAction.QUERY)) {
            operations.add(operation(descriptor.moduleAlias(), basePath + "/query", operationId(descriptor, "query"),
                    "Query " + mainEntity.title(), "WebQueryRequest", "WebPageResponse", PlatformAction.QUERY.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/view/{id}/associations/{viewCode}/query",
                    operationId(descriptor, "queryAssociation"),
                    "Query association " + mainEntity.title(), "WebQueryRequest", "WebPageResponse",
                    PlatformAction.QUERY.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/view/{id}/associations/{viewCode}/diagnose",
                    operationId(descriptor, "diagnoseAssociation"),
                    "Diagnose association " + mainEntity.title(), "WebQueryRequest", "DynamicAssociationViewDiagnosis",
                    PlatformAction.QUERY.code()));
        }
        if (standardActionVisible.test(PlatformAction.VIEW)) {
            operations.add(getOperation(descriptor.moduleAlias(), basePath + "/associations/relation-overview",
                    operationId(descriptor, "associationRelationOverview"),
                    "Association relation overview " + descriptor.title(), null, "DynamicAssociationRelationOverview",
                    PlatformAction.VIEW.code()));
            operations.add(getOperation(descriptor.moduleAlias(), basePath + "/associations/design",
                    operationId(descriptor, "associationDesign"),
                    "Association design " + descriptor.title(), null, "DynamicAssociationViewDescriptorList",
                    PlatformAction.VIEW.code()));
            operations.add(getOperation(descriptor.moduleAlias(), basePath + "/tasks/definitions",
                    operationId(descriptor, "taskDefinitions"),
                    "Module task definitions " + descriptor.title(), null, "PlatformModuleTaskDefinitionList",
                    PlatformAction.VIEW.code()));
            operations.add(getOperation(descriptor.moduleAlias(), basePath + "/view/{id}", operationId(descriptor, "view"),
                    "View " + mainEntity.title(), null, "DynamicRecordResponse", PlatformAction.VIEW.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/view/{id}/attachments/query",
                    operationId(descriptor, "queryAttachments"),
                    "Query attachments " + mainEntity.title(), null, "RecordAttachmentList", PlatformAction.VIEW.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/view/{id}/attachments/{attachmentId}/preview-ticket",
                    operationId(descriptor, "attachmentPreviewTicket"),
                    "Issue attachment preview ticket " + mainEntity.title(), null, "RecordAttachmentAccess",
                    PlatformAction.VIEW.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/view/{id}/attachments/{attachmentId}/download-ticket",
                    operationId(descriptor, "attachmentDownloadTicket"),
                    "Issue attachment download ticket " + mainEntity.title(), null, "RecordAttachmentAccess",
                    PlatformAction.VIEW.code()));
        }
        if (standardActionVisible.test(PlatformAction.CREATE)) {
            operations.add(operation(descriptor.moduleAlias(), basePath + "/insert", operationId(descriptor, "insert"),
                    "Insert " + mainEntity.title(), "DynamicRecordPayload", "DynamicRecordResponse", PlatformAction.CREATE.code()));
        }
        if (standardActionVisible.test(PlatformAction.UPDATE)) {
            operations.add(operation(descriptor.moduleAlias(), basePath + "/update/{id}", operationId(descriptor, "update"),
                    "Update " + mainEntity.title(), "DynamicRecordPayload", "DynamicRecordResponse", PlatformAction.UPDATE.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/view/{id}/attachments/add",
                    operationId(descriptor, "addAttachment"),
                    "Add attachment " + mainEntity.title(), "RecordAttachmentCommand", "RecordAttachmentList",
                    PlatformAction.UPDATE.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/view/{id}/attachments/upload-ticket",
                    operationId(descriptor, "attachmentUploadTicket"),
                    "Issue attachment upload ticket " + mainEntity.title(), null, "RecordAttachmentAccess",
                    PlatformAction.UPDATE.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/view/{id}/attachments/update/{attachmentId}",
                    operationId(descriptor, "updateAttachment"),
                    "Update attachment " + mainEntity.title(), "RecordAttachmentCommand", "RecordAttachmentList",
                    PlatformAction.UPDATE.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/view/{id}/attachments/delete/{attachmentId}",
                    operationId(descriptor, "deleteAttachment"),
                    "Delete attachment " + mainEntity.title(), null, "RecordAttachmentList",
                    PlatformAction.UPDATE.code()));
        }
        if (standardActionVisible.test(PlatformAction.DELETE)) {
            operations.add(operation(descriptor.moduleAlias(), basePath + "/delete/{id}", operationId(descriptor, "delete"),
                    "Delete " + mainEntity.title(), null, "integer", PlatformAction.DELETE.code()));
        }
        boolean exchangeSupported = mainEntity.capabilities().contains(EntityCapability.EXCHANGE.name());
        if (exchangeSupported && standardActionVisible.test(PlatformAction.IMPORT)) {
            operations.add(binaryOperation(descriptor.moduleAlias(), basePath + "/exchange/template",
                    operationId(descriptor, "exchangeTemplate"),
                    "Download exchange template " + mainEntity.title(), "DynamicExchangeTemplateRequest",
                    PlatformAction.IMPORT.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/import/parse", operationId(descriptor, "importParse"),
                    "Parse import workbook " + mainEntity.title(), "DynamicImportParseRequest",
                    "DynamicImportParseResult", PlatformAction.IMPORT.code()));
            operations.add(operation(descriptor.moduleAlias(), basePath + "/import/execute", operationId(descriptor, "importExecute"),
                    "Execute import workbook " + mainEntity.title(), "DynamicImportExecuteMultipartRequest",
                    "DynamicImportUploadResult", PlatformAction.IMPORT.code()));
            operations.add(binaryOperation(descriptor.moduleAlias(), basePath + "/import/error-file/{token}",
                    operationId(descriptor, "importErrorFile"),
                    "Download import error workbook " + mainEntity.title(), null,
                    PlatformAction.IMPORT.code()));
        }
        if (exchangeSupported && standardActionVisible.test(PlatformAction.EXPORT)) {
            operations.add(binaryOperation(descriptor.moduleAlias(), basePath + "/export/data",
                    operationId(descriptor, "exportData"),
                    "Export data " + mainEntity.title(), "WebQueryRequest", PlatformAction.EXPORT.code()));
            operations.add(binaryOperation(descriptor.moduleAlias(), basePath + "/export/selected",
                    operationId(descriptor, "exportSelected"),
                    "Export selected data " + mainEntity.title(), "DynamicSelectedExportRequest", PlatformAction.EXPORT.code()));
        }
        addCapabilityOperations(operations, descriptor, mainEntity, basePath, standardActionVisible);
        addTreeCapabilityOperations(operations, descriptor, mainEntity, basePath, standardActionVisible);
        addCapabilityHttpEndpoints(operations, descriptor, mainEntity, basePath, standardActionVisible);
        operations.add(getOperation(descriptor.moduleAlias(), basePath + "/actions", operationId(descriptor, "actions"),
                "List module actions", null, "DynamicActionDescriptorList", null));
        operations.add(getOperation(descriptor.moduleAlias(), basePath + "/actions/{recordId}", operationId(descriptor, "recordActions"),
                "List record actions", null, "DynamicWebActionAvailabilityList", null));
        descriptor.actions().stream()
                .filter(DynamicActionDescriptor::enabled)
                .filter(action -> hasActionPath(action, standardActionVisible))
                .filter(action -> action.actionLevel() != null)
                .forEach(action -> operations.addAll(actionOperations(descriptor, action, basePath)));
        descriptor.actions().stream()
                .filter(DynamicActionDescriptor::enabled)
                .filter(action -> action.actionLevel() == EntityActionLevel.RECORD
                        || action.actionLevel() == EntityActionLevel.ANY)
                .filter(action -> action.category() != EntityActionCategory.STANDARD)
                .filter(action -> !PlatformWebPathRules.isReservedWebActionCode(action.code()))
                .forEach(action -> operations.add(operationWithPermissionCode(descriptor.moduleAlias(),
                        basePath + "/" + action.code() + "/duplicate/check",
                        operationId(descriptor, "duplicateCheck" + upperName(action.code())),
                        "Duplicate check " + action.title(),
                        "DynamicWebDuplicateCheckRequest",
                        "RecordDuplicateCheckResult",
                        action.code(),
                        actionPermissionCode(descriptor.moduleAlias(), action))));
        if (standardActionVisible.test(PlatformAction.REFERENCE)) {
            mainEntity.fields().stream()
                    .filter(field -> field.reference() != null)
                    .forEach(field -> operations.add(operation(descriptor.moduleAlias(), basePath + "/references/" + field.fieldName() + "/resolve",
                            operationId(descriptor, "resolve" + upperName(field.fieldName())),
                            "Resolve reference " + field.title(),
                            "DynamicWebReferenceRequest",
                            "DynamicReferenceResolveResponse",
                            PlatformAction.REFERENCE.code())));
        }
        return List.copyOf(operations);
    }

    private void addCapabilityOperations(List<DynamicOpenApiDocument.Operation> operations,
                                         DynamicModuleDescriptor descriptor,
                                         DynamicEntityDescriptor mainEntity,
                                         String basePath,
                                         Predicate<PlatformAction> standardActionVisible) {
        CapabilityModuleRegistry.defaultRegistry().modules().stream()
                .filter(module -> mainEntity.capabilities().contains(module.capability().name()))
                .flatMap(module -> module.actionContribution().standardActions().stream()
                        .map(action -> Map.entry(action, module.actionContribution())))
                .filter(entry -> standardActionVisible.test(entry.getKey()))
                .forEach(entry -> addCapabilityOperation(operations, descriptor, mainEntity, basePath,
                        entry.getKey(), entry.getValue(),
                        entry.getKey() == PlatformAction.SORT
                                && mainEntity.capabilities().contains(EntityCapability.TREE.name())));
    }

    private void addCapabilityOperation(List<DynamicOpenApiDocument.Operation> operations,
                                        DynamicModuleDescriptor descriptor,
                                        DynamicEntityDescriptor mainEntity,
                                        String basePath,
                                        PlatformAction action,
                                        net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution contribution,
                                        boolean treeBridge) {
        contribution.endpointProjection(action)
                .flatMap(projection -> contribution.webActionContract(action, treeBridge)
                        .map(contract -> operation(descriptor.moduleAlias(), basePath + projection.path(),
                                operationId(descriptor, projection.operationCode()),
                                action.title() + " " + mainEntity.title(), contract.openApiRequestSchema(),
                                contract.openApiResponseSchema(), action.code())))
                .ifPresent(operations::add);
    }

    private void addTreeCapabilityOperations(List<DynamicOpenApiDocument.Operation> operations,
                                             DynamicModuleDescriptor descriptor,
                                             DynamicEntityDescriptor mainEntity,
                                             String basePath,
                                             Predicate<PlatformAction> standardActionVisible) {
        if (!mainEntity.capabilities().contains(EntityCapability.TREE.name())
                || !standardActionVisible.test(PlatformAction.TREE)) return;
        CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.TREE,
                        net.ximatai.muyun.spring.dynamic.capability.TreeCapabilityModule.class).actions()
                .webEndpointProjections().forEach(projection -> operations.add(getOperation(descriptor.moduleAlias(),
                        basePath + projection.path(), operationId(descriptor,
                                "subtree".equals(projection.operationCode()) ? "treeNode" : projection.operationCode()),
                        ("subtree".equals(projection.operationCode()) ? "Tree node " : "Tree ") + mainEntity.title(),
                        null, "WebListResponse", PlatformAction.TREE.code())));
    }

    private void addCapabilityHttpEndpoints(List<DynamicOpenApiDocument.Operation> operations,
                                            DynamicModuleDescriptor descriptor,
                                            DynamicEntityDescriptor mainEntity,
                                            String basePath,
                                            Predicate<PlatformAction> standardActionVisible) {
        CapabilityModuleRegistry.defaultRegistry().modules().stream()
                .filter(module -> mainEntity.capabilities().contains(module.capability().name()))
                .flatMap(module -> module.actionContribution().dynamicHttpEndpoints().stream())
                .filter(endpoint -> standardActionVisible.test(endpoint.action()))
                .forEach(endpoint -> operations.add(operation(descriptor.moduleAlias(),
                        basePath + endpoint.endpoint().path(), operationId(descriptor, endpoint.endpoint().operationCode()),
                        endpoint.action().title() + " " + mainEntity.title(), endpoint.openApiRequestSchema(),
                        endpoint.openApiResponseSchema(), endpoint.action().code())));
    }

    private boolean hasActionPath(DynamicActionDescriptor action,
                                  Predicate<PlatformAction> standardActionVisible) {
        if (action.category() != EntityActionCategory.STANDARD) {
            return !PlatformWebPathRules.isReservedWebActionCode(action.code());
        }
        return PlatformWebPathRules.isStandardActionPathCode(action.code())
                && PlatformAction.fromCode(action.code())
                .filter(standardActionVisible::test)
                .isPresent();
    }

    private List<DynamicOpenApiDocument.Operation> actionOperations(DynamicModuleDescriptor descriptor,
                                                                    DynamicActionDescriptor action,
                                                                    String basePath) {
        return switch (action.actionLevel()) {
            case LIST -> List.of(actionOperation(descriptor, action, basePath + "/" + action.code(), "list"));
            case RECORD -> List.of(actionOperation(descriptor, action, basePath + "/" + action.code() + "/{recordId}", "record"));
            case BATCH -> List.of(actionOperation(descriptor, action, basePath + "/" + action.code() + "/batch", "batch"));
            case ANY -> List.of(
                    actionOperation(descriptor, action, basePath + "/" + action.code(), "list"),
                    actionOperation(descriptor, action, basePath + "/" + action.code() + "/{recordId}", "record"),
                    actionOperation(descriptor, action, basePath + "/" + action.code() + "/batch", "batch")
            );
        };
    }

    private DynamicOpenApiDocument.Operation actionOperation(DynamicModuleDescriptor descriptor,
                                                            DynamicActionDescriptor action,
                                                            String path,
                                                            String scope) {
        return operationWithPermissionCode(descriptor.moduleAlias(), path,
                operationId(descriptor, scope + upperName(action.code())),
                action.title(),
                "DynamicWebActionRequest",
                "DynamicWebActionExecutionResponse",
                action.code(),
                actionPermissionCode(descriptor.moduleAlias(), action));
    }

    private String actionPermissionCode(String moduleAlias, DynamicActionDescriptor action) {
        if (action.permission() != null) {
            return action.permission().permissionCode();
        }
        String permissionActionCode = action.authInheritActionCode() == null
                ? PlatformAction.permissionActionCodeOf(action.code())
                : PlatformAction.permissionActionCodeOf(action.authInheritActionCode());
        return PlatformPermissionCode.action(moduleAlias, permissionActionCode);
    }

    private DynamicOpenApiDocument.Operation operation(String moduleAlias,
                                                       String path,
                                                       String operationId,
                                                       String summary,
                                                       String requestSchema,
                                                       String responseSchema,
                                                       String actionCode) {
        return operation(METHOD_POST, moduleAlias, path, operationId, summary, requestSchema, responseSchema, actionCode);
    }

    private DynamicOpenApiDocument.Operation binaryOperation(String moduleAlias,
                                                             String path,
                                                             String operationId,
                                                             String summary,
                                                             String requestSchema,
                                                             String actionCode) {
        return operationWithPermissionCode(METHOD_POST, moduleAlias, path, operationId, summary, requestSchema,
                "BinaryFile", actionCode, null, XLSX_CONTENT_TYPE);
    }

    private DynamicOpenApiDocument.Operation getOperation(String moduleAlias,
                                                          String path,
                                                          String operationId,
                                                          String summary,
                                                          String requestSchema,
                                                          String responseSchema,
                                                          String actionCode) {
        return operation(METHOD_GET, moduleAlias, path, operationId, summary, requestSchema, responseSchema, actionCode);
    }

    private DynamicOpenApiDocument.Operation operation(String method,
                                                       String moduleAlias,
                                                       String path,
                                                       String operationId,
                                                       String summary,
                                                       String requestSchema,
                                                       String responseSchema,
                                                       String actionCode) {
        return operationWithPermissionCode(method, moduleAlias, path, operationId, summary, requestSchema, responseSchema,
                actionCode, null);
    }

    private DynamicOpenApiDocument.Operation operationWithPermissionCode(String moduleAlias,
                                                                         String path,
                                                                         String operationId,
                                                                         String summary,
                                                                         String requestSchema,
                                                                         String responseSchema,
                                                                         String actionCode,
                                                                         String permissionCode) {
        return operationWithPermissionCode(METHOD_POST, moduleAlias, path, operationId, summary, requestSchema,
                responseSchema, actionCode, permissionCode);
    }

    private DynamicOpenApiDocument.Operation operationWithPermissionCode(String method,
                                                                         String moduleAlias,
                                                                         String path,
                                                                         String operationId,
                                                                         String summary,
                                                                         String requestSchema,
                                                                         String responseSchema,
                                                                         String actionCode,
                                                                         String permissionCode) {
        return operationWithPermissionCode(method, moduleAlias, path, operationId, summary, requestSchema,
                responseSchema, actionCode, permissionCode, null);
    }

    private DynamicOpenApiDocument.Operation operationWithPermissionCode(String method,
                                                                         String moduleAlias,
                                                                         String path,
                                                                         String operationId,
                                                                         String summary,
                                                                         String requestSchema,
                                                                         String responseSchema,
                                                                         String actionCode,
                                                                         String permissionCode,
                                                                         String responseMediaType) {
        String effectivePermissionCode = permissionCode == null && actionCode != null
                ? PlatformPermissionCode.action(moduleAlias, PlatformAction.permissionActionCodeOf(actionCode))
                : permissionCode;
        String effectiveRequestSchema = PlatformAction.DELETE.code().equals(actionCode)
                && path.endsWith("/delete/{id}") && requestSchema == null
                ? "RecordActionWebRequest"
                : requestSchema;
        int successStatus = PlatformAction.CREATE.code().equals(actionCode) && path.endsWith("/insert") ? 201 : 200;
        return new DynamicOpenApiDocument.Operation(
                method,
                path,
                operationId,
                summary,
                effectiveRequestSchema,
                responseSchema,
                actionCode,
                effectivePermissionCode,
                DEFAULT_ERRORS,
                successStatus,
                responseMediaType,
                PlatformAction.QUERY.code().equals(actionCode) ? Map.of() : null
        );
    }

    private Map<String, DynamicOpenApiDocument.Schema> schemas(DynamicEntityDescriptor entity) {
        return new DynamicOpenApiSchemaFactory().schemas(entity);
    }

    private Map<String, DynamicOpenApiDocument.ErrorResponse> errors() {
        return Map.of(
                PlatformErrorCodes.VALIDATION_FAILED,
                new DynamicOpenApiDocument.ErrorResponse(PlatformErrorCodes.VALIDATION_FAILED, 400, "PlatformWebError"),
                "DYNAMIC_ACTION_FAILED",
                new DynamicOpenApiDocument.ErrorResponse("DYNAMIC_ACTION_FAILED", 400, "PlatformWebError"),
                PlatformErrorCodes.CONFLICT_VERSION,
                new DynamicOpenApiDocument.ErrorResponse(PlatformErrorCodes.CONFLICT_VERSION, 409, "PlatformWebError"),
                PlatformErrorCodes.RESOURCE_NOT_FOUND,
                new DynamicOpenApiDocument.ErrorResponse(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404, "PlatformWebError"),
                PlatformErrorCodes.CONFIG_MISSING,
                new DynamicOpenApiDocument.ErrorResponse(PlatformErrorCodes.CONFIG_MISSING, 409, "PlatformWebError"),
                PlatformErrorCodes.INTERNAL_ERROR,
                new DynamicOpenApiDocument.ErrorResponse(PlatformErrorCodes.INTERNAL_ERROR, 500, "PlatformWebError")
        );
    }

    private String operationId(DynamicModuleDescriptor descriptor, String suffix) {
        return lowerName(descriptor.moduleAlias()) + upperName(suffix);
    }

    private String upperModuleName(String value) {
        return upperName(lowerName(value));
    }

    private String upperName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = lowerName(value);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String lowerName(String value) {
        StringBuilder result = new StringBuilder();
        boolean upperNext = false;
        for (char c : value.toCharArray()) {
            if (c == '.' || c == '_' || c == '-') {
                upperNext = result.length() > 0;
                continue;
            }
            result.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return result.toString();
    }
}
