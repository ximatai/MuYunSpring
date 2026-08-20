package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionResultBody;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldForm;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicLocalEditActionExecutorTest {
    @Test
    void shouldUpdateRecordWithinPublishedUiConfigFieldRange() {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService fieldService = mock(ModuleMetadataFieldService.class);
        when(snapshotService.snapshot("crm.contract")).thenReturn(snapshot(nameField(false), amountField(false)));
        when(fieldService.resolve("field-name")).thenReturn(field("field-name", RelationRole.MAIN, null, "name"));
        when(fieldService.resolve("field-amount")).thenReturn(field("field-amount", RelationRole.MAIN, null, "amount"));
        DynamicLocalEditActionExecutor executor = new DynamicLocalEditActionExecutor(snapshotService, fieldService);
        DynamicActionOperations operations = mock(DynamicActionOperations.class);
        when(operations.update(org.mockito.Mockito.any(DynamicRecord.class))).thenReturn(1);
        DynamicRecord record = record();
        record.setId("contract-1");
        record.setVersion(3);
        record.setValue("name", "Contract A");

        Object result = executor.execute(context(), DynamicActionExecutionRequest.record(record)
                        .withPayload(Map.of("uiConfigId", "ui-local"))
                        .withFieldNames(List.of("name")),
                operations);

        assertThat(result).isInstanceOf(DynamicActionResultBody.class);
        assertThat(((DynamicActionResultBody) result).refresh()).isTrue();
        verify(operations).update(record);
    }

    @Test
    void shouldRejectFieldsOutsideLocalEditUiConfig() {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService fieldService = mock(ModuleMetadataFieldService.class);
        when(snapshotService.snapshot("crm.contract")).thenReturn(snapshot(nameField(false)));
        when(fieldService.resolve("field-name")).thenReturn(field("field-name", RelationRole.MAIN, null, "name"));
        DynamicLocalEditActionExecutor executor = new DynamicLocalEditActionExecutor(snapshotService, fieldService);
        DynamicRecord record = record();
        record.setId("contract-1");
        record.setVersion(3);
        record.setValue("amount", new BigDecimal("100"));

        assertThatThrownBy(() -> executor.execute(context(), DynamicActionExecutionRequest.record(record)
                        .withPayload(Map.of("uiConfigId", "ui-local")),
                mock(DynamicActionOperations.class)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void shouldRejectReadOnlyLocalEditField() {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService fieldService = mock(ModuleMetadataFieldService.class);
        when(snapshotService.snapshot("crm.contract")).thenReturn(snapshot(nameField(true)));
        when(fieldService.resolve("field-name")).thenReturn(field("field-name", RelationRole.MAIN, null, "name"));
        DynamicLocalEditActionExecutor executor = new DynamicLocalEditActionExecutor(snapshotService, fieldService);
        DynamicRecord record = record();
        record.setId("contract-1");
        record.setVersion(3);
        record.setValue("name", "Contract A");

        assertThatThrownBy(() -> executor.execute(context(), DynamicActionExecutionRequest.record(record)
                        .withPayload(Map.of("uiConfigId", "ui-local")),
                mock(DynamicActionOperations.class)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void shouldRejectChildFieldsInLocalEditUiConfig() {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService fieldService = mock(ModuleMetadataFieldService.class);
        when(snapshotService.snapshot("crm.contract")).thenReturn(snapshot(nameField(false)));
        when(fieldService.resolve("field-name")).thenReturn(field("field-name", RelationRole.CHILD, "lines", "name"));
        DynamicLocalEditActionExecutor executor = new DynamicLocalEditActionExecutor(snapshotService, fieldService);
        DynamicRecord record = record();
        record.setId("contract-1");
        record.setVersion(3);
        record.setValue("name", "Contract A");

        assertThatThrownBy(() -> executor.execute(context(), DynamicActionExecutionRequest.record(record)
                        .withPayload(Map.of("uiConfigId", "ui-local")),
                mock(DynamicActionOperations.class)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("only supports main record fields");
    }

    @Test
    void shouldRejectLocalEditWithoutVersion() {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService fieldService = mock(ModuleMetadataFieldService.class);
        DynamicLocalEditActionExecutor executor = new DynamicLocalEditActionExecutor(snapshotService, fieldService);
        DynamicRecord record = record();
        record.setId("contract-1");
        record.setValue("name", "Contract A");

        assertThatThrownBy(() -> executor.execute(context(), DynamicActionExecutionRequest.record(record)
                        .withPayload(Map.of("uiConfigId", "ui-local")),
                mock(DynamicActionOperations.class)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("record.version");
    }

    @Test
    void shouldRejectUiConfigNotBoundToLocalEditAction() {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService fieldService = mock(ModuleMetadataFieldService.class);
        PlatformUiConfigField nameField = nameField(false);
        PlatformPageConfigSnapshot snapshot = snapshot(nameField);
        snapshot.uiConfigs().getFirst().setLayoutJson("""
                {
                  "blocks": [
                    {"type": "localEdit", "actionCode": "otherAction"}
                  ]
                }
                """);
        when(snapshotService.snapshot("crm.contract")).thenReturn(snapshot);
        DynamicLocalEditActionExecutor executor = new DynamicLocalEditActionExecutor(snapshotService, fieldService);
        DynamicRecord record = record();
        record.setId("contract-1");
        record.setVersion(3);
        record.setValue("name", "Contract A");

        assertThatThrownBy(() -> executor.execute(context(), DynamicActionExecutionRequest.record(record)
                        .withPayload(Map.of("uiConfigId", "ui-local")),
                mock(DynamicActionOperations.class)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not bound to action");
    }

    private DynamicActionExecutionContext context() {
        return new DynamicActionExecutionContext("crm.contract", "contract", "editBaseInfo",
                null, "contract-1", "trace-1", "tenant-a", false, null);
    }

    private DynamicRecord record() {
        return new DynamicRecord(new EntityDefinition(
                "contract",
                "crm_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("name", "Name").required(),
                        FieldDefinition.decimal("amount", "Amount")
                )
        ));
    }

    private PlatformPageConfigSnapshot snapshot(PlatformUiConfigField... fields) {
        PlatformUiConfig config = new PlatformUiConfig();
        config.setId("ui-local");
        config.setUiSetId("set-local");
        config.setClientType(PlatformUiClientType.WEB);
        config.setPublished(true);
        config.setLayoutJson("""
                {
                  "blocks": [
                    {"type": "localEdit", "actionCode": "editBaseInfo"}
                  ]
                }
                """);
        return new PlatformPageConfigSnapshot("crm.contract", List.of(), List.of(config), List.of(fields),
                List.of(), List.of());
    }

    private PlatformUiConfigField nameField(boolean readOnly) {
        return uiField("field-name", readOnly);
    }

    private PlatformUiConfigField amountField(boolean readOnly) {
        return uiField("field-amount", readOnly);
    }

    private PlatformUiConfigField uiField(String fieldId, boolean readOnly) {
        PlatformUiConfigField field = new PlatformUiConfigField();
        field.setId("ui-" + fieldId);
        field.setUiConfigId("ui-local");
        field.setModuleMetadataFieldId(fieldId);
        field.setVisible(true);
        field.setReadOnly(readOnly);
        return field;
    }

    private ResolvedModuleMetadataField field(String fieldId,
                                             RelationRole role,
                                             String relationAlias,
                                             String fieldName) {
        return new ResolvedModuleMetadataField(fieldId, "crm.contract", null, relationAlias, role,
                "contract", "contract", "Contract", fieldId, fieldName, fieldName, fieldName, "string",
                MetadataFieldForm.PHYSICAL);
    }
}
