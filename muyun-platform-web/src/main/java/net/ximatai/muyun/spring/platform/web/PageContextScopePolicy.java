package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes the record-scope semantics implied by required navigator list bindings.
 *
 * <p>A navigator is not an authorization source. Its selected value is a page-workspace
 * context, supplied through the explicit {@value #CONTEXT_HEADER} request contract. The server
 * nevertheless owns the binding: it rejects an absent required selection, applies the selection
 * on create, and verifies the selected scope before reading or mutating an existing record.</p>
 */
public final class PageContextScopePolicy {
    public static final String CONTEXT_HEADER = "X-MuYun-Page-Context";

    private PageContextScopePolicy() {
    }

    /** Appends list or navigator-reference criteria, rejecting missing required navigator values. */
    public static Criteria criteria(List<PageContextBindingDefinition> bindings, Map<String, ?> requestValues,
                                    boolean navigatorValuesRequired) {
        Criteria criteria = Criteria.of();
        if (bindings == null || bindings.isEmpty()) return criteria;
        for (PageContextBindingDefinition binding : bindings) {
            Object value = resolveQueryValue(binding, requestValues);
            boolean required = binding.source() == PageContextSource.NAVIGATOR
                    && (navigatorValuesRequired || binding.navigatorListQueryMode() == NavigatorListQueryMode.REQUIRED_SCOPE);
            if (value == null) {
                if (required) throw missingScope(binding);
                continue;
            }
            criteria.eq(binding.targetKey(), value);
        }
        return criteria;
    }

    /** Required record scope is the mutation/view counterpart of a required navigator list scope. */
    public static List<PageContextBindingDefinition> recordScopeBindings(List<PageContextBindingDefinition> bindings) {
        if (bindings == null || bindings.isEmpty()) return List.of();
        return bindings.stream()
                .filter(binding -> binding.source() == PageContextSource.NAVIGATOR)
                .filter(binding -> binding.target() == PageContextTarget.LIST_QUERY)
                .filter(binding -> binding.navigatorListQueryMode() == NavigatorListQueryMode.REQUIRED_SCOPE)
                .toList();
    }

    /** Resolves the current explicit page context into record field values. */
    public static Map<String, Object> requiredRecordScopeValues(List<PageContextBindingDefinition> bindings) {
        if (bindings == null || bindings.isEmpty()) return Map.of();
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) return Map.of();
        Map<String, Object> context = requestContextValues();
        Map<String, Object> values = new LinkedHashMap<>();
        for (PageContextBindingDefinition binding : bindings) {
            Object value = contextValue(binding, context);
            if (value == null) throw missingScope(binding);
            values.put(binding.targetKey(), value);
        }
        return Map.copyOf(values);
    }

    /** Applies the current required page scope to a static record before create. */
    public static <T> T applyForCreate(T record, List<PageContextBindingDefinition> bindings) {
        Map<String, Object> scope = requiredRecordScopeValues(bindings);
        if (scope.isEmpty()) return record;
        BeanWrapper properties = properties(record);
        scope.forEach((field, value) -> {
            if (!properties.isWritableProperty(field)) {
                throw new IllegalArgumentException("page record scope field is not writable: " + field);
            }
            properties.setPropertyValue(field, value);
        });
        return record;
    }

    /** Verifies a static record belongs to the current required page scope. */
    public static void requireRecordInScope(Object record, List<PageContextBindingDefinition> bindings) {
        Map<String, Object> scope = requiredRecordScopeValues(bindings);
        if (scope.isEmpty()) return;
        BeanWrapper properties = properties(record);
        requireRecordValues(scope, field -> {
            if (!properties.isReadableProperty(field)) {
                throw new IllegalArgumentException("page record scope field is not readable: " + field);
            }
            return properties.getPropertyValue(field);
        });
    }

    /** Verifies arbitrary record representations, including dynamic records, against page scope values. */
    public static void requireRecordValues(Map<String, Object> scope,
                                           java.util.function.Function<String, Object> fieldValue) {
        if (scope == null || scope.isEmpty()) return;
        scope.forEach((field, expected) -> {
            Object actual = fieldValue.apply(field);
            if (!Objects.equals(actual, expected)) {
                throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                        "Record does not belong to the current page scope: " + field);
            }
        });
    }

    private static Object resolveQueryValue(PageContextBindingDefinition binding, Map<String, ?> requestValues) {
        return PageContextServerValueResolver.resolve(binding).orElseGet(() ->
                requestValues == null ? null : requestValues.get(binding.targetKey()));
    }

    private static Map<String, Object> requestContextValues() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Map.of();
        }
        HttpServletRequest request = attributes.getRequest();
        Object cached = request.getAttribute(PageContextScopePolicy.class.getName() + ".values");
        if (cached instanceof Map<?, ?> values) {
            Map<String, Object> copied = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (key != null) copied.putIfAbsent(key.toString(), value);
            });
            return Map.copyOf(copied);
        }
        Map<String, Object> values = PageContextRequestHeader.parse(request.getHeader(CONTEXT_HEADER));
        request.setAttribute(PageContextScopePolicy.class.getName() + ".values", values);
        return values;
    }

    private static Object contextValue(PageContextBindingDefinition binding, Map<String, Object> context) {
        Object value = context.get(binding.sourceKey());
        return value == null ? context.get(binding.targetKey()) : value;
    }

    private static BeanWrapper properties(Object record) {
        if (record == null) throw new IllegalArgumentException("page scoped record must not be null");
        return new BeanWrapperImpl(record);
    }

    private static RuntimeException missingScope(PageContextBindingDefinition binding) {
        return PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                "Page navigator scope is required: " + binding.sourceKey());
    }
}
