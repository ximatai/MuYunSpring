package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicQueryCondition;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.web.ModuleQueryFormField;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

final class DynamicWebQueryFormSupport {
    private DynamicWebQueryFormSupport() {
    }

    static Criteria queryFormCriteria(String moduleAlias,
                                      WebQueryRequest request,
                                      PlatformPageConfigSnapshotService snapshotService,
                                      ModuleMetadataFieldService moduleFieldService,
                                      FieldUiControlService fieldUiControlService,
                                      FieldUiControlBindingService fieldUiControlBindingService,
                                      Function<List<DynamicQueryCondition>, Criteria> conditionCompiler) {
        if (request == null || request.queryForm().isEmpty()) {
            return Criteria.of();
        }
        Map<String, Object> effectiveValues = effectiveValues(request.queryForm());
        if (effectiveValues.isEmpty()) {
            return Criteria.of();
        }
        if (!hasText(request.uiConfigId())) {
            throw new PlatformException("Query form requires published LIST uiConfigId");
        }
        if (snapshotService == null || moduleFieldService == null) {
            throw new PlatformException("query form services are not configured");
        }
        PlatformPageConfigSnapshot snapshot = snapshotService.snapshot(moduleAlias);
        PlatformUiConfig uiConfig = publishedUiConfig(snapshot, request.uiConfigId());
        requireListUiConfig(snapshot, uiConfig);
        Map<String, ModuleQueryFormField> fields = visibleMainFields(snapshot, uiConfig, moduleFieldService,
                fieldUiControlService, fieldUiControlBindingService);
        List<DynamicQueryCondition> conditions = new ArrayList<>();
        for (Map.Entry<String, Object> entry : effectiveValues.entrySet()) {
            String fieldName = normalizeKey(entry.getKey());
            if (!hasText(fieldName)) {
                continue;
            }
            ModuleQueryFormField field = fields.get(fieldName);
            if (field == null) {
                throw new PlatformException("Query form field is not available in UI config: " + fieldName);
            }
            DynamicQueryCondition condition = condition(field, entry.getValue());
            if (condition != null) {
                conditions.add(condition);
            }
        }
        return conditions.isEmpty() ? Criteria.of() : conditionCompiler.apply(conditions);
    }

    private static Map<String, Object> effectiveValues(Map<String, Object> queryForm) {
        Map<String, Object> effective = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : queryForm.entrySet()) {
            if (!isEmptyValue(entry.getValue())) {
                effective.put(entry.getKey(), entry.getValue());
            }
        }
        return effective;
    }

    private static Map<String, ModuleQueryFormField> visibleMainFields(PlatformPageConfigSnapshot snapshot,
                                                                 PlatformUiConfig uiConfig,
                                                                 ModuleMetadataFieldService moduleFieldService,
                                                                 FieldUiControlService fieldUiControlService,
                                                                 FieldUiControlBindingService bindingService) {
        Map<String, ModuleQueryFormField> fields = new LinkedHashMap<>();
        Map<String, FieldUiControl> controls = new LinkedHashMap<>();
        Map<String, List<String>> bindingKeys = new LinkedHashMap<>();
        for (PlatformUiConfigField field : snapshot.uiFields()) {
            if (!Objects.equals(field.getUiConfigId(), uiConfig.getId())
                    || !Boolean.TRUE.equals(field.getVisible())) {
                continue;
            }
            ResolvedModuleMetadataField resolved = moduleFieldService.resolve(field.getModuleMetadataFieldId());
            if (resolved.relationRole() == RelationRole.MAIN) {
                if (!hasText(field.getFieldUiControlAlias())) {
                    fields.put(resolved.fieldName(), new ModuleQueryFormField(resolved.fieldName(),
                            ModuleQueryFormField.Mode.DEFAULT, List.of()));
                    continue;
                }
                if (fieldUiControlService == null || bindingService == null) {
                    throw new PlatformException("field UI control query services are not configured");
                }
                FieldUiControl control = controls.computeIfAbsent(field.getFieldUiControlAlias(),
                        fieldUiControlService::requireFieldUiControl);
                List<String> keys = bindingKeys.computeIfAbsent(control.getAlias(), alias -> bindingService
                        .listByFieldUiControlAliases(List.of(alias)).stream()
                        .map(FieldUiControlBinding::getValueKey)
                        .toList());
                fields.put(resolved.fieldName(), new ModuleQueryFormField(resolved.fieldName(),
                        control.getQueryMode() == null ? ModuleQueryFormField.Mode.DEFAULT
                                : ModuleQueryFormField.Mode.valueOf(control.getQueryMode().name()), keys));
            }
        }
        return fields;
    }

    static DynamicQueryCondition condition(ModuleQueryFormField field, Object value) {
        if (field.mode() == ModuleQueryFormField.Mode.BETWEEN) {
            RangeQueryValue range = rangeValue(field.fieldName(), value, field.bindingKeys());
            if (range.values().isEmpty()) {
                return null;
            }
            return new DynamicQueryCondition(field.fieldName(), DynamicQueryOperator.BETWEEN,
                    range.values(), range.timeZone());
        }
        List<?> values = values(value);
        if (values.isEmpty()) {
            return null;
        }
        return new DynamicQueryCondition(field.fieldName(), null, values);
    }

    private static RangeQueryValue rangeValue(String fieldName, Object value, List<String> bindingKeys) {
        if (value instanceof Map<?, ?> map) {
            List<?> values = values((bindingKeys == null ? List.<String>of() : bindingKeys).stream()
                    .map(map::get)
                    .toList());
            if (values.isEmpty()) {
                return new RangeQueryValue(List.of(), textValue(map.get("timeZone")));
            }
            if (values.size() != 2) {
                throw new PlatformException("Query form range requires start and end values: " + fieldName);
            }
            return new RangeQueryValue(values, textValue(map.get("timeZone")));
        }
        List<?> values = values(value);
        if (values.isEmpty()) return new RangeQueryValue(List.of(), null);
        if (values.size() != 2) throw new PlatformException("Query form range requires exactly two values: " + fieldName);
        return new RangeQueryValue(values, null);
    }


    private static PlatformUiConfig publishedUiConfig(PlatformPageConfigSnapshot snapshot, String uiConfigId) {
        return snapshot.uiConfigs().stream()
                .filter(config -> Objects.equals(config.getId(), uiConfigId))
                .findFirst()
                .orElseThrow(() -> new PlatformException("UI config is not published in module snapshot: "
                        + uiConfigId));
    }

    private static void requireListUiConfig(PlatformPageConfigSnapshot snapshot, PlatformUiConfig uiConfig) {
        PlatformUiSet uiSet = snapshot.uiSets().stream()
                .filter(set -> Objects.equals(set.getId(), uiConfig.getUiSetId()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("UI config set is not published in module snapshot: "
                        + uiConfig.getUiSetId()));
        if (uiSet.getSetType() != PlatformUiSetType.LIST) {
            throw new PlatformException("Query form requires LIST UI config: " + uiConfig.getId());
        }
    }

    private static List<?> values(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(item -> !isEmptyValue(item))
                    .toList();
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                Object item = Array.get(value, index);
                if (!isEmptyValue(item)) {
                    values.add(item);
                }
            }
            return values;
        }
        return List.of(value);
    }

    private static String normalizeKey(String key) {
        return key == null ? null : key.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value.getClass().isArray()) {
            return Array.getLength(value) == 0;
        }
        return false;
    }

    private static String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private record RangeQueryValue(List<?> values, String timeZone) {
    }
}
