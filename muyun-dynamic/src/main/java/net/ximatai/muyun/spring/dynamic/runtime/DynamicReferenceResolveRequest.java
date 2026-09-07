package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record DynamicReferenceResolveRequest(
        DynamicReferenceResolveMode mode,
        DynamicReferenceMatchMode matchMode,
        String fuzzy,
        List<Object> values,
        Criteria criteria,
        PageRequest pageRequest,
        boolean includeProjections,
        Map<String, Object> formValues
) {
    private static final PageRequest DEFAULT_PAGE = PageRequest.of(1, 20);

    public DynamicReferenceResolveRequest(DynamicReferenceResolveMode mode,
                                          DynamicReferenceMatchMode matchMode,
                                          String fuzzy,
                                          List<Object> values,
                                          Criteria criteria,
                                          PageRequest pageRequest,
                                          boolean includeProjections) {
        this(mode, matchMode, fuzzy, values, criteria, pageRequest, includeProjections, Map.of());
    }

    public DynamicReferenceResolveRequest {
        mode = mode == null ? DynamicReferenceResolveMode.QUERY : mode;
        matchMode = matchMode == null ? DynamicReferenceMatchMode.AUTO : matchMode;
        values = values == null ? List.of() : List.copyOf(values);
        pageRequest = pageRequest == null ? DEFAULT_PAGE : pageRequest;
        // Empty form fields are valid explicit nulls, not malformed request entries.
        formValues = formValues == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(formValues));
    }

    public static DynamicReferenceResolveRequest query(String fuzzy) {
        return new DynamicReferenceResolveRequest(DynamicReferenceResolveMode.QUERY, DynamicReferenceMatchMode.AUTO,
                fuzzy, List.of(), null, DEFAULT_PAGE, true);
    }

    public static DynamicReferenceResolveRequest translate(List<Object> values) {
        return new DynamicReferenceResolveRequest(DynamicReferenceResolveMode.TRANSLATE, DynamicReferenceMatchMode.AUTO,
                null, values, null, DEFAULT_PAGE, true);
    }

    public DynamicReferenceResolveRequest withCriteria(Criteria value) {
        return new DynamicReferenceResolveRequest(mode, matchMode, fuzzy, values, value, pageRequest,
                includeProjections, formValues);
    }

    public DynamicReferenceResolveRequest withMatchMode(DynamicReferenceMatchMode value) {
        return new DynamicReferenceResolveRequest(mode, value, fuzzy, values, criteria, pageRequest,
                includeProjections, formValues);
    }

    public DynamicReferenceResolveRequest withPage(PageRequest value) {
        return new DynamicReferenceResolveRequest(mode, matchMode, fuzzy, values, criteria, value,
                includeProjections, formValues);
    }

    public DynamicReferenceResolveRequest withoutProjections() {
        return new DynamicReferenceResolveRequest(mode, matchMode, fuzzy, values, criteria, pageRequest,
                false, formValues);
    }

    public DynamicReferenceResolveRequest withFormValues(Map<String, Object> value) {
        return new DynamicReferenceResolveRequest(mode, matchMode, fuzzy, values, criteria, pageRequest,
                includeProjections, value);
    }
}
