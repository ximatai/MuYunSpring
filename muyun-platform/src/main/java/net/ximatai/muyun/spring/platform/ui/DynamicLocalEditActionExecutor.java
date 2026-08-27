package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionResultBody;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DynamicLocalEditActionExecutor implements DynamicActionExecutor {
    public static final String EXECUTOR_KEY = "muyun.localEdit";

    private final PlatformPageConfigSnapshotService snapshotService;
    private final ModuleMetadataFieldService moduleFieldService;

    public DynamicLocalEditActionExecutor(PlatformPageConfigSnapshotService snapshotService,
                                          ModuleMetadataFieldService moduleFieldService) {
        this.snapshotService = snapshotService;
        this.moduleFieldService = moduleFieldService;
    }

    @Override
    public String executorKey() {
        return EXECUTOR_KEY;
    }

    @Override
    public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
        throw new UnsupportedOperationException("dynamic local edit requires action operations");
    }

    @Override
    public Object execute(DynamicActionExecutionContext context,
                          DynamicActionExecutionRequest request,
                          DynamicActionOperations operations) {
        DynamicActionExecutionRequest normalized = request == null
                ? DynamicActionExecutionRequest.empty()
                : request;
        DynamicRecord record = normalized.record();
        if (record == null) {
            throw new PlatformException("Local edit action requires record");
        }
        String recordId = firstText(normalized.recordId(), record.getId(), context.recordId());
        if (recordId == null) {
            throw new PlatformException("Local edit action requires recordId");
        }
        if (record.getVersion() == null) {
            throw new PlatformException("Local edit action requires record.version");
        }
        record.setId(recordId);
        String uiConfigId = payloadText(normalized.payload(), "uiConfigId");
        if (uiConfigId == null) {
            throw new PlatformException("Local edit action requires payload.uiConfigId");
        }
        validateFields(context.moduleAlias(), context.actionCode(), uiConfigId, normalized, record);
        return DynamicActionResultBody.changedCount(operations.update(record));
    }

    private void validateFields(String moduleAlias,
                                String actionCode,
                                String uiConfigId,
                                DynamicActionExecutionRequest request,
                                DynamicRecord record) {
        PlatformPageConfigSnapshot snapshot = snapshotService.snapshot(moduleAlias);
        PlatformUiConfig uiConfig = snapshot.uiConfigs().stream()
                .filter(config -> Objects.equals(config.getId(), uiConfigId))
                .findFirst()
                .orElseThrow(() -> new PlatformException("Local edit UI config is not published in module snapshot: "
                        + uiConfigId));
        validateActionBinding(uiConfig, actionCode, uiConfigId);
        Map<String, FieldDefinition> fields = record.getEntity().fields().stream()
                .collect(Collectors.toMap(FieldDefinition::fieldName, field -> field));
        Map<String, PlatformUiConfigField> editableFields = snapshot.uiFields().stream()
                .filter(field -> Objects.equals(field.getUiConfigId(), uiConfig.getId()))
                .filter(field -> Boolean.TRUE.equals(field.getVisible()))
                .collect(Collectors.toMap(field -> {
                    ResolvedModuleMetadataField resolved = moduleFieldService.resolve(field.getModuleMetadataFieldId());
                    if (resolved.relationRole() != RelationRole.MAIN) {
                        throw new PlatformException("Local edit only supports main record fields: "
                                + resolved.relationAlias());
                    }
                    return resolved.fieldName();
                }, field -> field, (left, right) -> left));
        Set<String> allowed = allowedFields(request, record);
        if (allowed.isEmpty()) {
            throw new PlatformException("Local edit requires at least one field");
        }
        if (!editableFields.keySet().containsAll(allowed)) {
            LinkedHashSet<String> invalid = new LinkedHashSet<>(allowed);
            invalid.removeAll(editableFields.keySet());
            throw new PlatformException("Local edit field is not allowed by UI config: " + invalid);
        }
        for (String fieldName : allowed) {
            FieldDefinition field = fields.get(fieldName);
            if (field == null) {
                throw new PlatformException("Local edit field is not defined: " + fieldName);
            }
            PlatformUiConfigField uiField = editableFields.get(fieldName);
            if (Boolean.TRUE.equals(uiField.getReadOnly())) {
                throw new PlatformException("Local edit field is read-only: " + fieldName);
            }
            boolean required = Boolean.TRUE.equals(uiField.getRequiredOverride()) || field.isRequired();
            Object value = record.getValues().get(fieldName);
            if (required && (value == null || value instanceof String text && text.isBlank())) {
                throw new PlatformException("Local edit required field is missing: " + fieldName);
            }
        }
    }

    private void validateActionBinding(PlatformUiConfig uiConfig,
                                       String actionCode,
                                       String uiConfigId) {
        if (actionCode == null || actionCode.isBlank()) {
            throw new PlatformException("Local edit action binding is missing actionCode");
        }
        if (!hasLocalEditBinding(uiConfig, actionCode.trim())) {
            throw new PlatformException("Local edit UI config is not bound to action: "
                    + uiConfigId + "." + actionCode);
        }
    }

    private boolean hasLocalEditBinding(PlatformUiConfig uiConfig, String actionCode) {
        String layoutJson = uiConfig.getLayoutJson();
        if (layoutJson == null || layoutJson.isBlank()) {
            return false;
        }
        JsonNode root = PlatformPageLayout.root(uiConfig);
        JsonNode blocks = root.get("blocks");
        if (blocks == null || !blocks.isArray()) {
            return false;
        }
        for (JsonNode block : blocks) {
            if (block == null || !block.isObject()) {
                continue;
            }
            if ("localEdit".equals(text(block, "type")) && actionCode.equals(text(block, "actionCode"))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> allowedFields(DynamicActionExecutionRequest request, DynamicRecord record) {
        LinkedHashSet<String> fields = request.fieldNames().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (fields.isEmpty()) {
            fields.addAll(record.explicitFieldCodes());
        }
        if (!fields.containsAll(record.explicitFieldCodes())) {
            LinkedHashSet<String> extra = new LinkedHashSet<>(record.explicitFieldCodes());
            extra.removeAll(fields);
            throw new PlatformException("Local edit record contains fields outside fieldNames: " + extra);
        }
        return fields;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private String payloadText(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
