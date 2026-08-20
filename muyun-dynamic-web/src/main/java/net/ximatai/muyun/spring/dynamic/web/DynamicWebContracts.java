package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryCriteria;
import net.ximatai.muyun.spring.web.WebSort;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionResult;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionResultBody;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicFormulaPreviewResult;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceMatchMode;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveMode;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.platform.impact.RecordOriginContext;
import net.ximatai.muyun.spring.common.web.PlatformWebWireContract;

import java.util.Collection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record DynamicRecordPayload(String id,
                            Integer version,
                            Map<String, Object> values,
                            Map<String, List<DynamicRecordPayload>> children) {
    DynamicRecordPayload {
        values = values == null ? Map.of() : Map.copyOf(values);
        if (children == null) {
            children = Map.of();
        } else {
            Map<String, List<DynamicRecordPayload>> normalizedChildren = new LinkedHashMap<>();
            children.forEach((relationCode, rows) ->
                    normalizedChildren.put(relationCode, rows == null ? null : List.copyOf(rows)));
            children = Collections.unmodifiableMap(normalizedChildren);
        }
    }

    static DynamicRecordPayload empty() {
        return new DynamicRecordPayload(null, null, Map.of(), Map.of());
    }
}

record DynamicWebActionRequest(String recordId,
                               DynamicRecordPayload record,
                               List<String> ids,
                               List<String> orderedIds,
                               String beforeId,
                               String afterId,
                               String parentId,
                               List<WebQueryCondition> conditions,
                               WebPageRequest page,
                               List<WebSort> sorts,
                               List<String> fieldNames,
                               Map<String, Object> payload) {
    DynamicWebActionRequest {
        ids = ids == null ? List.of() : List.copyOf(ids);
        orderedIds = orderedIds == null ? List.of() : List.copyOf(orderedIds);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    static DynamicWebActionRequest empty() {
        return new DynamicWebActionRequest(null, null, List.of(), List.of(), null, null, null,
                List.of(), null, List.of(), List.of(), Map.of());
    }
}

record DynamicWebReferenceRequest(DynamicReferenceResolveMode mode,
                                  DynamicReferenceMatchMode matchMode,
                                  String fuzzy,
                                  List<Object> values,
                                  List<WebQueryCondition> conditions,
                                  WebQueryCriteria criteria,
                                  WebPageRequest page,
                                  Boolean includeProjections,
                                  Map<String, Object> formValues,
                                  String sourceUiConfigId,
                                  String uiConfigId,
                                  String queryTemplateId,
                                  Map<String, Object> externalQueryValues) {
    DynamicWebReferenceRequest {
        values = values == null ? List.of() : List.copyOf(values);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        includeProjections = includeProjections == null || includeProjections;
        formValues = formValues == null ? Map.of() : Map.copyOf(formValues);
        externalQueryValues = externalQueryValues == null ? Map.of() : Map.copyOf(externalQueryValues);
    }

    static DynamicWebReferenceRequest empty() {
        return new DynamicWebReferenceRequest(null, null, null, List.of(), List.of(), null,
                WebPageRequest.DEFAULT, true, Map.of(), null, null, null, Map.of());
    }
}

record DynamicWebReferenceGenerationRequest(String sourceRecordId) {
}

record DynamicWebDuplicateCheckRequest(String recordId,
                                       Map<String, Object> values) {
    DynamicWebDuplicateCheckRequest {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    static DynamicWebDuplicateCheckRequest empty() {
        return new DynamicWebDuplicateCheckRequest(null, Map.of());
    }
}

record DynamicSummaryConfigItem(String detailId,
                                String calcType,
                                String label,
                                Integer precision,
                                String formatter) {
}

record DynamicSummaryItem(String detailId,
                          String calcType,
                          String label,
                          Integer precision,
                          String formatter,
                          Object value) {
}

record DynamicWebGenerationConfirmRequest(String targetModuleAlias,
                                          String targetEntityAlias,
                                          DynamicRecordPayload record,
                                          RecordOriginContext originContext) {
}

record DynamicModuleTaskCheckRequest(String uiConfigId) {
}

record DynamicFormulaPreviewRequest(DynamicRecordPayload record) {
}

record DynamicFormulaReportResponse(List<FormulaRuntimeReport.Issue> warnings,
                                    List<FormulaRuntimeReport.Issue> errors) {
    static DynamicFormulaReportResponse from(FormulaRuntimeReport report) {
        FormulaRuntimeReport normalized = report == null ? new FormulaRuntimeReport() : report;
        return new DynamicFormulaReportResponse(normalized.warnings(), normalized.errors());
    }
}

record DynamicFormulaPreviewResponse(DynamicRecordResponse record,
                                     DynamicFormulaReportResponse report,
                                     List<String> changedFields) {
    static DynamicFormulaPreviewResponse from(DynamicFormulaPreviewResult result) {
        return new DynamicFormulaPreviewResponse(
                DynamicRecordResponse.from(result.record()),
                DynamicFormulaReportResponse.from(result.report()),
                result.changedFields()
        );
    }
}

record DynamicRecordResponse(String id,
                             Integer version,
                             Map<String, Object> values,
                             Map<String, List<DynamicRecordResponse>> children) {
    static DynamicRecordResponse from(DynamicRecord record) {
        if (record == null) {
            return null;
        }
        Map<String, List<DynamicRecordResponse>> childResponses = record.getChildren().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue() == null
                                ? List.of()
                                : entry.getValue().stream().map(DynamicRecordResponse::from).toList()
                ));
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) DynamicWebValues.webValue(record.outputValues(FieldOutputContext.VIEW));
        values = DynamicWebValues.losslessNumericWireValues(record, values);
        return new DynamicRecordResponse(record.getId(), record.getVersion(), values, childResponses);
    }
}

record DynamicPageResponse(List<Object> records,
                           long total,
                           int pageNum,
                           int pageSize,
                           long pages,
                           boolean totalKnown) {
    static DynamicPageResponse from(PageResult<?> page) {
        return new DynamicPageResponse(
                page.getRecords().stream().map(DynamicWebValues::webValue).toList(),
                page.getTotal(),
                page.getPageNum(),
                page.getPageSize(),
                page.getPages(),
                page.isTotalKnown()
        );
    }
}

record DynamicWebActionExecutionResponse(DynamicWebActionContext context, DynamicWebActionResultBody body) {
    static DynamicWebActionExecutionResponse from(DynamicActionExecutionResult result) {
        return new DynamicWebActionExecutionResponse(
                DynamicWebActionContext.from(result.context()),
                DynamicWebActionResultBody.from(result.body())
        );
    }
}

record DynamicWebActionContext(String moduleAlias,
                               String actionCode,
                               String actionLevel,
                               String executorType,
                               String recordId,
                               String traceId) {
    static DynamicWebActionContext from(DynamicActionExecutionContext context) {
        if (context == null) {
            return null;
        }
        return new DynamicWebActionContext(
                context.moduleAlias(),
                context.actionCode(),
                context.action().actionLevel().name(),
                context.action().executorType().name(),
                context.recordId(),
                context.traceId()
        );
    }
}

record DynamicWebActionResultBody(String type,
                                  Object value,
                                  String message,
                                  boolean refresh,
                                  String redirectTo,
                                  Object refreshStrategy) {
    static DynamicWebActionResultBody from(DynamicActionResultBody body) {
        return new DynamicWebActionResultBody(
                body.type().name(),
                DynamicWebValues.webValue(body.value()),
                body.message(),
                body.refresh(),
                body.redirectTo(),
                DynamicWebValues.webValue(body.refreshStrategy())
        );
    }
}

final class DynamicWebValues {
    private DynamicWebValues() {
    }

    static Object webValue(Object value) {
        if (value instanceof DynamicRecord record) {
            return DynamicRecordResponse.from(record);
        }
        if (value instanceof PageResult<?> page) {
            return DynamicPageResponse.from(page);
        }
        if (value instanceof Criteria) {
            throw new IllegalArgumentException("dynamic web response does not expose internal Criteria");
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(DynamicWebValues::webValue).toList();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), webValue(item)));
            return converted;
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof Instant instant) {
            return instant.truncatedTo(ChronoUnit.SECONDS).toString();
        }
        return value;
    }

    /**
     * JavaScript cannot faithfully load an int64 or arbitrary decimal JSON number. Dynamic
     * record HTTP therefore uses the same textual wire form for LONG/DECIMAL responses as the
     * standard form editor uses for mutations.
     */
    static Map<String, Object> losslessNumericWireValues(DynamicRecord record, Map<String, Object> values) {
        LinkedHashMap<String, Object> converted = new LinkedHashMap<>(values);
        record.getEntity().fields().forEach(field -> {
            Object value = converted.get(field.fieldName());
            if (value == null) {
                return;
            }
            converted.put(field.fieldName(), PlatformWebWireContract.responseValue(field.type().name(), value));
        });
        return converted;
    }
}
