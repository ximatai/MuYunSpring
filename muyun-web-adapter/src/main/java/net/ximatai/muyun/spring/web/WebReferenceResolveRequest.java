package net.ximatai.muyun.spring.web;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source-neutral input for a field-reference candidate query.
 *
 * <p>Rule providers may use the optional UI and query-template context; a
 * simple static reference can safely ignore it and still honour query and
 * translation semantics.</p>
 */
public record WebReferenceResolveRequest(
        WebReferenceResolveMode mode,
        WebReferenceMatchMode matchMode,
        String fuzzy,
        List<Object> values,
        List<WebQueryCondition> conditions,
        WebQueryCriteria criteria,
        WebPageRequest page,
        Boolean includeProjections,
        Map<String, Object> formValues,
        WebReferenceSource source,
        String sourceUiConfigId,
        String uiConfigId,
        String queryTemplateId,
        Map<String, Object> externalQueryValues
) {
    public WebReferenceResolveRequest {
        values = values == null ? List.of() : List.copyOf(values);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        includeProjections = includeProjections == null || includeProjections;
        formValues = immutableMapAllowingNullValues(formValues);
        externalQueryValues = immutableMapAllowingNullValues(externalQueryValues);
    }

    public static WebReferenceResolveRequest empty() {
        return new WebReferenceResolveRequest(null, null, null, List.of(), List.of(), null,
                WebPageRequest.DEFAULT, true, Map.of(), null, null, null, null, Map.of());
    }

    /** Compatibility constructor for clients issued before source identity became explicit. */
    public WebReferenceResolveRequest(WebReferenceResolveMode mode,
                                      WebReferenceMatchMode matchMode,
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
        this(mode, matchMode, fuzzy, values, conditions, criteria, page, includeProjections, formValues,
                null, sourceUiConfigId, uiConfigId, queryTemplateId, externalQueryValues);
    }

    private static Map<String, Object> immutableMapAllowingNullValues(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
