package net.ximatai.muyun.spring.web;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public record WebQueryRequest(WebPageRequest page,
                              Boolean unpaged,
                              List<WebQueryCondition> conditions,
                              WebQueryCriteria criteria,
                              Map<String, Object> queryForm,
                              List<WebSort> sorts,
                              String uiConfigId,
                              String queryTemplateId,
                              Map<String, Object> externalQueryValues,
                              Boolean navigationSession,
                              String quickSearch,
                              List<String> quickSearchFields,
                              String navigationQueryKey,
                              String navigatorHostModuleAlias,
                              String navigatorTargetLevelKey) {
    public WebQueryRequest {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        queryForm = queryForm == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(queryForm));
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
        externalQueryValues = externalQueryValues == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(externalQueryValues));
        quickSearchFields = quickSearchFields == null ? List.of() : List.copyOf(quickSearchFields);
    }

    public WebQueryRequest(WebPageRequest page,
                           List<WebQueryCondition> conditions,
                           List<WebSort> sorts) {
        this(page, null, conditions, null, Map.of(), sorts, null, null, Map.of(), null, null, List.of(), null, null, null);
    }

    public WebQueryRequest(WebPageRequest page, Boolean unpaged, List<WebQueryCondition> conditions,
                           WebQueryCriteria criteria, Map<String, Object> queryForm, List<WebSort> sorts,
                           String uiConfigId, String queryTemplateId, Map<String, Object> externalQueryValues,
                           Boolean navigationSession, String quickSearch, List<String> quickSearchFields,
                           String navigationQueryKey) {
        this(page, unpaged, conditions, criteria, queryForm, sorts, uiConfigId, queryTemplateId, externalQueryValues,
                navigationSession, quickSearch, quickSearchFields, navigationQueryKey, null, null);
    }

    public WebPageRequest pageOrDefault() {
        return page == null ? WebPageRequest.DEFAULT : page;
    }

    /** Preserves request routing facts while a server-side context resolver replaces trusted values. */
    public WebQueryRequest withExternalQueryValues(Map<String, Object> values) {
        return new WebQueryRequest(page, unpaged, conditions, criteria, queryForm, sorts, uiConfigId, queryTemplateId,
                values, navigationSession, quickSearch, quickSearchFields, navigationQueryKey,
                navigatorHostModuleAlias, navigatorTargetLevelKey);
    }

    public boolean navigationSessionEnabled() {
        return Boolean.TRUE.equals(navigationSession);
    }

    public boolean unpagedEnabled() {
        return Boolean.TRUE.equals(unpaged);
    }
}
