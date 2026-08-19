package net.ximatai.muyun.spring.dynamic.openapi;

import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicOpenApiGeneratorTest {
    private final DynamicOpenApiGenerator generator = new DynamicOpenApiGenerator();

    @Test
    void shouldGenerateStableDynamicModuleOpenApiDocument() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(module()));

        assertThat(document.moduleAlias()).isEqualTo("sales.contract");
        assertThat(document.basePath()).isEqualTo("/sales.contract");
        assertThat(document.schemas()).containsKey("RecordActionWebRequest");
        assertThat(document.operations()).filteredOn(operation -> "/sales.contract/insert".equals(operation.path()))
                .singleElement().extracting(DynamicOpenApiDocument.Operation::successStatus).isEqualTo(201);
        assertThat(document.operations()).filteredOn(operation -> "/sales.contract/delete/{id}".equals(operation.path()))
                .singleElement().extracting(DynamicOpenApiDocument.Operation::requestSchema)
                .isEqualTo("RecordActionWebRequest");
        assertThat(document.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .contains(
                        "/sales.contract/describe",
                        "/sales.contract/query",
                        "/sales.contract/query/summary",
                        "/sales.contract/view/{id}",
                        "/sales.contract/tasks/definitions",
                        "/sales.contract/view/{id}/attachments/query",
                        "/sales.contract/view/{id}/attachments/add",
                        "/sales.contract/view/{id}/attachments/upload-ticket",
                        "/sales.contract/view/{id}/attachments/{attachmentId}/preview-ticket",
                        "/sales.contract/view/{id}/attachments/{attachmentId}/download-ticket",
                        "/sales.contract/view/{id}/attachments/update/{attachmentId}",
                        "/sales.contract/view/{id}/attachments/delete/{attachmentId}",
                        "/sales.contract/insert",
                        "/sales.contract/update/{id}",
                        "/sales.contract/delete/{id}",
                        "/sales.contract/import/parse",
                        "/sales.contract/import/execute",
                        "/sales.contract/import/error-file/{token}",
                        "/sales.contract/exchange/template",
                        "/sales.contract/export/data",
                        "/sales.contract/export/selected",
                        "/sales.contract/actions",
                        "/sales.contract/actions/{recordId}",
                        "/sales.contract/batchDelete/batch",
                        "/sales.contract/publish",
                        "/sales.contract/submit/{recordId}",
                        "/sales.contract/submit/duplicate/check",
                        "/sales.contract/archive/batch",
                        "/sales.contract/preview",
                        "/sales.contract/preview/{recordId}",
                        "/sales.contract/preview/duplicate/check",
                        "/sales.contract/preview/batch",
                        "/sales.contract/references/customerId/resolve"
                );
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/import/error-file/{token}"))
                .findFirst())
                .get()
                .satisfies(operation -> {
                    assertThat(operation.method()).isEqualTo("POST");
                    assertThat(operation.operationId()).isEqualTo("salesContractImportErrorFile");
                    assertThat(operation.responseSchema()).isEqualTo("BinaryFile");
                    assertThat(operation.responseMediaType())
                            .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/exchange/template")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.IMPORT.code());
                    assertThat(operation.requestSchema()).isEqualTo("DynamicExchangeTemplateRequest");
                    assertThat(operation.responseSchema()).isEqualTo("BinaryFile");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/export/selected")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.EXPORT.code());
                    assertThat(operation.requestSchema()).isEqualTo("DynamicSelectedExportRequest");
                    assertThat(operation.responseSchema()).isEqualTo("BinaryFile");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/query/summary")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.QUERY.code());
                    assertThat(operation.requestSchema()).isEqualTo("WebQueryRequest");
                    assertThat(operation.responseSchema()).isEqualTo("DynamicSummaryItemList");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/submit/{recordId}"))
                .findFirst())
                .get()
                .satisfies(operation -> {
                    assertThat(operation.method()).isEqualTo("POST");
                    assertThat(operation.operationId()).isEqualTo("salesContractRecordSubmit");
                    assertThat(operation.requestSchema()).isEqualTo("DynamicWebActionRequest");
                    assertThat(operation.responseSchema()).isEqualTo("DynamicWebActionExecutionResponse");
                    assertThat(operation.actionCode()).isEqualTo("submit");
                    assertThat(operation.permissionCode()).isEqualTo("sales.contract:submit");
                    assertThat(operation.errorCodes())
                            .contains("DYNAMIC_ACTION_FAILED", PlatformErrorCodes.CONFLICT_VERSION)
                            .doesNotContain("DYNAMIC_CONFLICT");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/insert"))
                .findFirst())
                .get()
                .satisfies(operation -> {
                    assertThat(operation.requestSchema()).isEqualTo("DynamicRecordPayload");
                    assertThat(operation.responseSchema()).isEqualTo("DynamicRecordResponse");
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.CREATE.code());
                    assertThat(operation.permissionCode()).isEqualTo("sales.contract:create");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/update/{id}"))
                .findFirst())
                .get()
                .satisfies(operation -> {
                    assertThat(operation.requestSchema()).isEqualTo("DynamicRecordPayload");
                    assertThat(operation.responseSchema()).isEqualTo("DynamicRecordResponse");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/view/{id}/attachments/query")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.VIEW.code());
                    assertThat(operation.responseSchema()).isEqualTo("RecordAttachmentList");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/view/{id}/attachments/add")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.UPDATE.code());
                    assertThat(operation.requestSchema()).isEqualTo("RecordAttachmentCommand");
                    assertThat(operation.responseSchema()).isEqualTo("RecordAttachmentList");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/view/{id}/attachments/upload-ticket")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.UPDATE.code());
                    assertThat(operation.responseSchema()).isEqualTo("RecordAttachmentAccess");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/view/{id}/attachments/{attachmentId}/preview-ticket")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.VIEW.code());
                    assertThat(operation.responseSchema()).isEqualTo("RecordAttachmentAccess");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/view/{id}/attachments/{attachmentId}/download-ticket")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.VIEW.code());
                    assertThat(operation.responseSchema()).isEqualTo("RecordAttachmentAccess");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/submit/duplicate/check")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.requestSchema()).isEqualTo("DynamicWebDuplicateCheckRequest");
                    assertThat(operation.responseSchema()).isEqualTo("RecordDuplicateCheckResult");
                    assertThat(operation.actionCode()).isEqualTo("submit");
                    assertThat(operation.permissionCode()).isEqualTo("sales.contract:submit");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/view/{id}"))
                .findFirst())
                .get()
                .satisfies(operation -> {
                    assertThat(operation.method()).isEqualTo("GET");
                    assertThat(operation.responseSchema()).isEqualTo("DynamicRecordResponse");
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.VIEW.code());
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/batchDelete/batch")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.requestSchema()).isEqualTo("DynamicWebActionRequest");
                    assertThat(operation.responseSchema()).isEqualTo("DynamicWebActionExecutionResponse");
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.BATCH_DELETE.code());
                    assertThat(operation.permissionCode()).isEqualTo("sales.contract:delete");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/query")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.QUERY.code());
                    assertThat(operation.permissionCode()).isEqualTo("sales.contract:view");
                });
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/actions")))
                .singleElement()
                .extracting(DynamicOpenApiDocument.Operation::method)
                .isEqualTo("GET");
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/actions/{recordId}")))
                .singleElement()
                .extracting(DynamicOpenApiDocument.Operation::method)
                .isEqualTo("GET");
        assertThat(document.schemas()).containsKey("WebQueryCriteria");
        assertThat(document.schemas().get("WebQueryRequest").properties())
                .containsKey("criteria");
        assertThat(document.schemas().get("DynamicWebReferenceRequest").properties())
                .containsKey("criteria");
        assertThat(document.schemas().get("WebQueryCriteria").properties().get("groups").itemType())
                .isEqualTo("WebQueryCriteria");
    }

    @Test
    void shouldGenerateDefaultCrudModuleWithoutExposingInternalStandardActions() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(contractEntity()))));

        assertThat(document.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .contains("/sales.contract/query", "/sales.contract/insert", "/sales.contract/batchDelete/batch")
                .doesNotContain("/sales.contract/create", "/sales.contract/select/{recordId}",
                        "/sales.contract/queryCriteria", "/sales.contract/count");
    }

    @Test
    void shouldHideStandardBatchDeleteActionPathWhenBatchDeleteActionIsNotVisible() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(module()),
                action -> action != PlatformAction.BATCH_DELETE);

        assertThat(document.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .contains("/sales.contract/delete/{id}")
                .doesNotContain("/sales.contract/batchDelete/batch");
    }

    @Test
    void shouldNotExposeCustomActionsThatConflictWithReservedWebPaths() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(
                        action("openapi", "OpenAPI", EntityActionLevel.LIST),
                        action("query", "Query", EntityActionLevel.LIST),
                        action("import", "Import", EntityActionLevel.LIST),
                        action("exchange", "Exchange", EntityActionLevel.LIST),
                        action("export", "Export", EntityActionLevel.LIST),
                        action("enable", "Enable", EntityActionLevel.RECORD),
                        action("disable", "Disable", EntityActionLevel.RECORD),
                        action("sort", "Sort", EntityActionLevel.RECORD),
                        action("tree", "Tree", EntityActionLevel.LIST)
                ))
                .build();

        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(module));

        assertThat(document.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .contains("/sales.contract/query",
                        "/sales.contract/import/parse", "/sales.contract/exchange/template",
                        "/sales.contract/export/data", "/sales.contract/export/selected")
                .doesNotContain("/sales.contract/openapi", "/sales.contract/openapi/{recordId}", "/sales.contract/query/{recordId}",
                        "/sales.contract/import", "/sales.contract/exchange", "/sales.contract/export");
        assertThat(document.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/view/{id}/associations/{viewCode}/query")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.method()).isEqualTo("POST");
                    assertThat(operation.requestSchema()).isEqualTo("WebQueryRequest");
                    assertThat(operation.responseSchema()).isEqualTo("WebPageResponse");
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.QUERY.code());
                });
    }

    @Test
    void shouldHideAssociationDesignOpenApiWhenViewActionIsNotVisible() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(module()),
                action -> action == PlatformAction.QUERY);

        assertThat(document.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .contains("/sales.contract/query",
                        "/sales.contract/view/{id}/associations/{viewCode}/query",
                        "/sales.contract/view/{id}/associations/{viewCode}/diagnose")
                .doesNotContain("/sales.contract/associations/relation-overview",
                        "/sales.contract/associations/design",
                        "/sales.contract/tasks/definitions",
                        "/sales.contract/view/{id}");
    }

    @Test
    void shouldExposeSortWebOperationOnlyWhenMainEntitySupportsSort() {
        DynamicOpenApiDocument plain = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(contractEntity()))));
        DynamicOpenApiDocument sortable = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(sortableEntity()))));
        DynamicOpenApiDocument tree = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(treeEntity()))));

        assertThat(plain.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .doesNotContain("/sales.contract/sort/{id}");
        assertThat(sortable.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/sort/{id}"))
                .findFirst())
                .get()
                .satisfies(operation -> {
                    assertThat(operation.requestSchema()).isEqualTo("SortWebRequest");
                    assertThat(operation.responseSchema()).isEqualTo("integer");
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.SORT.code());
                });
        assertThat(sortable.schemas().get("SortWebRequest").properties())
                .containsKeys("previousId", "nextId")
                .doesNotContainKey("parentId")
                .doesNotContainKey("orderedIds");
        assertThat(tree.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/sort/{id}"))
                .findFirst())
                .get()
                .satisfies(operation -> assertThat(operation.requestSchema()).isEqualTo("TreeSortWebRequest"));
        assertThat(tree.schemas().get("TreeSortWebRequest").properties())
                .containsKeys("previousId", "nextId", "parentId")
                .doesNotContainKey("orderedIds");
    }

    @Test
    void shouldExposeRecycleBinLifecycleOnlyWhenMainEntitySupportsRecycleBin() {
        DynamicOpenApiDocument plain = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(contractEntityWithoutExchange()))));
        DynamicOpenApiDocument recycleBin = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(recycleBinEntity()))));

        assertThat(plain.operations()).extracting(DynamicOpenApiDocument.Operation::path)
                .doesNotContain("/sales.contract/recycle-bin/query");
        assertThat(recycleBin.operations()).extracting(DynamicOpenApiDocument.Operation::path).contains(
                "/sales.contract/recycle-bin/query", "/sales.contract/recycle-bin/view/{id}",
                "/sales.contract/recycle-bin/{sourceDeleteOperationId}/restore",
                "/sales.contract/recycle-bin/{sourceDeleteOperationId}/purge");
        assertThat(recycleBin.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/recycle-bin/query"))).singleElement()
                .satisfies(operation -> {
                    assertThat(operation.requestSchema()).isEqualTo("WebQueryRequest");
                    assertThat(operation.responseSchema()).isEqualTo("RecycleBinItemPage");
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.RECYCLE_BIN_QUERY.code());
                });
        assertThat(generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(recycleBinEntity()))),
                action -> action != PlatformAction.RECYCLE_BIN_QUERY)
                .operations()).extracting(DynamicOpenApiDocument.Operation::path)
                .doesNotContain("/sales.contract/recycle-bin/query", "/sales.contract/recycle-bin/view/{id}")
                .contains("/sales.contract/recycle-bin/{sourceDeleteOperationId}/restore",
                        "/sales.contract/recycle-bin/{sourceDeleteOperationId}/purge");
    }

    @Test
    void shouldExposeExchangeOperationsOnlyWhenMainEntitySupportsExchange() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(contractEntityWithoutExchange()))));

        assertThat(document.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .doesNotContain(
                        "/sales.contract/import/parse",
                        "/sales.contract/import/execute",
                        "/sales.contract/import/error-file/{token}",
                        "/sales.contract/exchange/template",
                        "/sales.contract/export/data",
                        "/sales.contract/export/selected"
                );
    }

    @Test
    void shouldExposeEnableWebOperationsOnlyWhenMainEntitySupportsEnable() {
        DynamicOpenApiDocument plain = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(contractEntity()))));
        DynamicOpenApiDocument enabled = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(enabledEntity()))));

        assertThat(plain.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .doesNotContain("/sales.contract/enable/{id}", "/sales.contract/disable/{id}");
        assertThat(enabled.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .contains("/sales.contract/enable/{id}", "/sales.contract/disable/{id}");
        assertThat(enabled.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/enable/{id}"))
                .findFirst())
                .get()
                .satisfies(operation -> {
                    assertThat(operation.responseSchema()).isEqualTo("integer");
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.ENABLE.code());
                });
        assertThat(enabled.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/disable/{id}")))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.DISABLE.code());
                    assertThat(operation.permissionCode()).isEqualTo("sales.contract:enable");
                });
    }

    @Test
    void shouldExposeTreeWebOperationsOnlyWhenMainEntitySupportsTree() {
        DynamicOpenApiDocument plain = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(contractEntity()))));
        DynamicOpenApiDocument tree = generator.generate(DynamicModuleDescriptor.from(
                new ModuleDefinition("sales.contract", "Contract", List.of(treeEntity()))));

        assertThat(plain.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .doesNotContain("/sales.contract/tree", "/sales.contract/tree/{id}");
        assertThat(tree.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .contains("/sales.contract/tree", "/sales.contract/tree/{id}");
        assertThat(tree.operations().stream()
                .filter(operation -> operation.path().equals("/sales.contract/tree"))
                .findFirst())
                .get()
                .satisfies(operation -> {
                    assertThat(operation.responseSchema()).isEqualTo("WebListResponse");
                    assertThat(operation.actionCode()).isEqualTo(PlatformAction.TREE.code());
                });
        assertThat(tree.schemas().get("WebListResponse").properties().get("records").itemType())
                .isEqualTo("DynamicRecordResponse");
    }

    @Test
    void shouldGenerateRecordSchemaFromDynamicFields() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(module()));
        DynamicOpenApiDocument.Schema record = document.schemas().get("ContractRecord");
        DynamicOpenApiDocument.Schema schema = document.schemas().get("ContractValues");

        assertThat(record.properties().get("values").type()).isEqualTo("ContractValues");
        assertThat(schema.required()).contains("code", "amount");
        assertThat(schema.properties().get("code"))
                .satisfies(property -> {
                    assertThat(property.type()).isEqualTo("string");
                    assertThat(property.required()).isTrue();
                });
        assertThat(schema.properties().get("amount"))
                .satisfies(property -> {
                    assertThat(property.type()).isEqualTo("number");
                    assertThat(property.format()).isEqualTo("decimal");
                });
        assertThat(schema.properties().get("status"))
                .satisfies(property -> {
                    assertThat(property.optionSourceType()).isEqualTo("dictionary");
                    assertThat(property.optionSource()).isEqualTo("sales.contract_status");
                    assertThat(property.multiple()).isFalse();
                });
        assertThat(schema.properties().get("tags"))
                .satisfies(property -> {
                    assertThat(property.type()).isEqualTo("array");
                    assertThat(property.itemType()).isNull();
                    assertThat(property.multiple()).isTrue();
                    assertThat(property.optionSource()).isEqualTo("sales.contract_tag");
                });
        assertThat(schema.properties().get("customerId"))
                .satisfies(property -> {
                    assertThat(property.referenceModuleAlias()).isEqualTo("sales.customer");
                    assertThat(property.referenceEntityAlias()).isEqualTo("customer");
                });
        assertThat(schema.properties().get("signedAt"))
                .satisfies(property -> {
                    assertThat(property.type()).isEqualTo("string");
                    assertThat(property.format()).isEqualTo("date-time");
                    assertThat(property.temporalSemantics()).isEqualTo("ZONED_INSTANT");
                    assertThat(property.companionFields()).containsExactly("signedAtTimeZone");
                });
    }

    @Test
    void shouldExposePlatformWebErrorSchemas() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(module()));

        assertThat(document.schemas().get("WebQueryRequest").properties().get("conditions"))
                .satisfies(property -> {
                    assertThat(property.type()).isEqualTo("array");
                    assertThat(property.itemType()).isEqualTo("WebQueryCondition");
                    assertThat(property.companionFields()).isEmpty();
                });
        assertThat(document.schemas().get("WebQueryRequest").properties())
                .containsKeys("uiConfigId", "queryTemplateId", "externalQueryValues", "navigationSession",
                        "queryForm", "quickSearch", "quickSearchFields", "navigationQueryKey");
        assertThat(document.schemas().get("DynamicRecordPayload").properties().get("attachments").itemType())
                .isEqualTo("RecordAttachmentCommand");
        assertThat(document.schemas().get("RecordAttachmentCommand").required()).containsExactly("fileId");
        assertThat(document.schemas().get("RecordAttachmentList").items().type()).isEqualTo("RecordAttachment");
        assertThat(document.schemas().get("RecordAttachmentAccess").properties())
                .containsKeys("mode", "fileId", "accessToken", "url", "expiresAt", "metadata");
        assertThat(document.schemas().get("DynamicWebDuplicateCheckRequest").properties())
                .containsKeys("recordId", "values");
        assertThat(document.schemas().get("RecordDuplicateCheckResult").properties())
                .containsKeys("ruleId", "actionCode", "fieldNames", "duplicated", "matches");
        assertThat(document.errors())
                .containsEntry(PlatformErrorCodes.VALIDATION_FAILED,
                        new DynamicOpenApiDocument.ErrorResponse(PlatformErrorCodes.VALIDATION_FAILED, 400, "PlatformWebError"))
                .containsEntry("DYNAMIC_ACTION_FAILED",
                        new DynamicOpenApiDocument.ErrorResponse("DYNAMIC_ACTION_FAILED", 400, "PlatformWebError"))
                .containsEntry(PlatformErrorCodes.CONFLICT_VERSION,
                        new DynamicOpenApiDocument.ErrorResponse(PlatformErrorCodes.CONFLICT_VERSION, 409, "PlatformWebError"))
                .doesNotContainKeys("DYNAMIC_BAD_REQUEST", "DYNAMIC_UI_VALIDATION", "DYNAMIC_ATTACHMENT_ERROR",
                        "DYNAMIC_DUPLICATE_CHECK_ERROR", "DYNAMIC_CONFLICT");
        assertThat(document.schemas()).doesNotContainKeys("DynamicWebError", "DynamicWebActionError");
        assertThat(document.schemas().get("PlatformWebError").properties())
                .containsKeys("traceId", "code", "status", "message", "scope", "targets", "details");
        assertThat(document.schemas().get("PlatformWebError").required())
                .containsExactly("traceId", "code", "status", "message", "targets", "details");
        assertThat(document.schemas().get("ErrorTarget").properties())
                .containsKeys("kind", "moduleAlias", "entityAlias", "relationAlias", "fieldName", "rowIndex",
                        "recordId", "actionCode", "attachmentId");
    }

    @Test
    void shouldExposeWebResponseSchemasForActionAndCrudContracts() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(module()));

        assertThat(document.schemas().get("DynamicWebActionContext").properties())
                .containsKeys("moduleAlias", "actionCode", "actionLevel", "executorType", "recordId", "traceId");
        assertThat(document.schemas().get("DynamicWebActionContext").required())
                .containsExactly("moduleAlias", "actionCode", "actionLevel", "executorType");
        assertThat(document.schemas().get("DynamicWebActionResultBody").properties())
                .containsKeys("type", "value", "message", "refresh", "redirectTo", "refreshStrategy");
        assertThat(document.schemas().get("DynamicWebActionResultBody").valueShapeByResultType())
                .containsEntry("DIALOG", "DynamicActionDialog")
                .containsEntry("RECORD", "DynamicRecordResponse")
                .containsEntry("PAGE", "DynamicPageResponse")
                .containsEntry("COUNT", "integer");
        assertThat(document.schemas().get("DynamicActionDialog").required())
                .containsExactly("dialogKey");
        assertThat(document.schemas().get("DynamicActionDialog").properties())
                .containsKeys("dialogKey", "title", "actionCode", "submitActionCode", "submitPath", "recordId",
                        "refreshOnSuccess", "redirectTo", "refreshStrategy");
        assertThat(document.schemas().get("DynamicActionRefreshStrategy").properties())
                .containsKeys("list", "detail", "redirectToDetail", "redirectRecordId", "redirectModuleAlias");
        assertThat(document.schemas().get("DynamicWebActionAvailabilityResponse").properties())
                .containsKeys("action", "available", "message");
        assertThat(document.schemas().get("DynamicWebActionExecutionResponse").properties().get("body").type())
                .isEqualTo("DynamicWebActionResultBody");
        assertThat(document.schemas().get("DynamicWebActionAvailabilityList").items().type())
                .isEqualTo("DynamicWebActionAvailabilityResponse");
        assertThat(document.schemas().get("WebPageRequest").properties())
                .containsKeys("pageNum", "pageSize")
                .doesNotContainKeys("offset", "limit");
        assertThat(document.schemas().get("DynamicWebActionRequest").properties().get("conditions").itemType())
                .isEqualTo("WebQueryCondition");
        assertThat(document.schemas().get("DynamicWebActionRequest").properties().get("page").type())
                .isEqualTo("WebPageRequest");
        assertThat(document.schemas().get("DynamicWebActionRequest").properties().get("sorts").itemType())
                .isEqualTo("WebSort");
        assertThat(document.schemas().get("DynamicWebReferenceRequest").properties().get("conditions").itemType())
                .isEqualTo("WebQueryCondition");
        assertThat(document.schemas().get("WebQueryCondition").properties())
                .containsKeys("fieldName", "operator", "values", "timeZone");
        assertThat(document.schemas().get("DynamicWebReferenceRequest").properties().get("page").type())
                .isEqualTo("WebPageRequest");
        assertThat(document.schemas().get("DynamicWebReferenceRequest").properties())
                .containsKeys("formValues", "sourceUiConfigId", "uiConfigId", "queryTemplateId",
                        "externalQueryValues");
        assertThat(document.schemas().get("DynamicReferenceResolveResponse").properties())
                .containsKeys("options", "results", "offset", "limit", "total")
                .doesNotContainKeys("pageNum", "pageSize");
        assertThat(document.schemas().get("DynamicImportParseResult").properties().get("sheets").itemType())
                .isEqualTo("DynamicImportParseSheet");
        assertThat(document.schemas().get("DynamicExchangeTemplateRequest").properties())
                .containsKeys("disabledReferenceDropdownFields", "referenceDropdownLimit");
        assertThat(document.schemas().get("DynamicSelectedExportRequest").properties())
                .containsKeys("ids", "query");
        assertThat(document.schemas().get("DynamicSelectedExportRequest").required()).containsExactly("ids");
        assertThat(document.schemas().get("DynamicImportExecuteMultipartRequest").properties())
                .containsKeys("command", "file");
        assertThat(document.schemas().get("DynamicImportUploadResult").properties())
                .containsKeys("created", "updated", "skipped", "errorCount", "partialSuccess", "errorFileToken")
                .doesNotContainKeys("successCount", "failCount");
        assertThat(document.schemas()).doesNotContainKey("DynamicExportQueryRequest");
    }

    @Test
    void shouldExposeDescribeDocumentSchemasWithoutTheDocumentDeliveryContract() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(module()));

        assertThat(document.schemas().get("DynamicModuleDescriptor").properties())
                .containsKeys("moduleAlias", "title", "mainEntityAlias", "actions", "entities",
                        "relations", "references", "associationViews");
        assertThat(document.schemas()).doesNotContainKey("OpenApi31Document");
        assertThat(document.schemas().get("BinaryFile"))
                .extracting(DynamicOpenApiDocument.Schema::type, DynamicOpenApiDocument.Schema::format)
                .containsExactly("string", "binary");
        assertThat(document.operations()).extracting(DynamicOpenApiDocument.Operation::path)
                .doesNotContain("/sales.contract/openapi");
    }

    @Test
    void shouldDefineEveryReferencedSchema() {
        DynamicOpenApiDocument document = generator.generate(DynamicModuleDescriptor.from(module()));
        Set<String> primitives = Set.of("string", "object", "array", "integer", "number", "boolean",
                "scalar", "null");

        for (DynamicOpenApiDocument.Operation operation : document.operations()) {
            assertResolvableSchema(operation.requestSchema(), document.schemas(), primitives);
            assertResolvableSchema(operation.responseSchema(), document.schemas(), primitives);
        }
        for (DynamicOpenApiDocument.Schema schema : document.schemas().values()) {
            assertResolvableSchema(schema.type(), document.schemas(), primitives);
            if (schema.items() != null) {
                assertResolvableSchema(schema.items().type(), document.schemas(), primitives);
                assertResolvableSchema(schema.items().itemType(), document.schemas(), primitives);
            }
            for (DynamicOpenApiDocument.Property property : schema.properties().values()) {
                assertResolvableSchema(property.type(), document.schemas(), primitives);
                assertResolvableSchema(property.itemType(), document.schemas(), primitives);
            }
            for (String shape : schema.valueShapeByResultType().values()) {
                assertResolvableSchema(shape, document.schemas(), primitives);
            }
        }
    }

    private void assertResolvableSchema(String schemaName,
                                        Map<String, DynamicOpenApiDocument.Schema> schemas,
                                        Set<String> primitives) {
        if (schemaName == null || primitives.contains(schemaName)) {
            return;
        }
        assertThat(schemas)
                .as("schema reference must be defined: %s", schemaName)
                .containsKey(schemaName);
    }

    private ModuleDefinition module() {
        return ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(lineEntity(), contractEntity()))
                .references(List.of(EntityReferenceDefinition.to(
                        "contract", "customerId", "sales.customer.customer")))
                .actions(List.of(
                        action("publish", "发布", EntityActionLevel.LIST),
                        action("submit", "提交", EntityActionLevel.RECORD),
                        action("archive", "归档", EntityActionLevel.BATCH),
                        action("preview", "预览", EntityActionLevel.ANY)
                ))
                .mainEntityAlias("contract")
                .build();
    }

    private EntityActionDefinition action(String code,
                                          String title,
                                          EntityActionLevel level) {
        return new EntityActionDefinition("contract", code, title,
                true, level, EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED,
                true, false, null, null, null, EntityActionExecutorType.SERVICE, code + "Executor");
    }

    private EntityDefinition contractEntity() {
        return new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required(),
                FieldDefinition.string("status", "Status")
                        .dictionary("sales", "contract_status"),
                FieldDefinition.decimal("amount", "Amount").precision(18, 2).required(),
                FieldDefinition.of("tags", FieldType.JSON, "Tags")
                        .dictionary("sales", "contract_tag", OptionSelectionMode.MULTIPLE),
                FieldDefinition.string("customerId", "Customer"),
                FieldDefinition.zonedTimestamp("signedAt", "Signed At"),
                FieldDefinition.zonedTimestampTimeZone("signedAt", "signed_at")
        )).withCapabilities(EntityCapability.EXCHANGE);
    }

    private EntityDefinition contractEntityWithoutExchange() {
        return new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required()
        ));
    }

    private EntityDefinition lineEntity() {
        return new EntityDefinition("line", "sales_contract_line", "Contract Line", List.of(
                FieldDefinition.string("contractId", "Contract").required(),
                FieldDefinition.string("productName", "Product").length(128).required(),
                FieldDefinition.decimal("quantity", "Quantity").precision(18, 2)
        ));
    }

    private EntityDefinition treeEntity() {
        return new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required(),
                FieldDefinition.parentId(),
                FieldDefinition.sortOrder()
        )).withCapabilities(EntityCapability.TREE);
    }

    private EntityDefinition sortableEntity() {
        return new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required(),
                FieldDefinition.sortOrder()
        )).withCapabilities(EntityCapability.SORT);
    }

    private EntityDefinition enabledEntity() {
        return new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required(),
                FieldDefinition.enabled()
        )).withCapabilities(EntityCapability.ENABLE);
    }

    private EntityDefinition recycleBinEntity() {
        return new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required()
        )).withCapabilities(EntityCapability.RECYCLE_BIN);
    }
}
