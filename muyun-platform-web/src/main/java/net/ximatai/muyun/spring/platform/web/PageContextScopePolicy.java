package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
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
        return criteria(bindings, requestValues, navigatorValuesRequired, null, null, null);
    }

    /**
     * Applies page scope with optional server-resolved selection values. The opaque header is
     * never treated as a field map: it is passed to the registered resolver on every request.
     */
    public static Criteria criteria(List<PageContextBindingDefinition> bindings, Map<String, ?> requestValues,
                                    boolean navigatorValuesRequired,
                                    String moduleAlias, PlatformAction action,
                                    PageSelectionContextResolverRegistry selectionResolvers) {
        Criteria criteria = Criteria.of();
        if (bindings == null || bindings.isEmpty()) return criteria;
        for (PageContextBindingDefinition binding : bindings) {
            PageContextValue resolvedSelection = binding.source() == PageContextSource.RESOLVED_SELECTION
                    ? PageSelectionContextRuntime.requiredValue(binding, moduleAlias, action, selectionResolvers) : null;
            Object value = resolvedSelection == null
                    ? resolveQueryValue(binding, requestValues)
                    : resolvedSelection.value();
            boolean required = binding.source() == PageContextSource.NAVIGATOR
                    && (navigatorValuesRequired || binding.navigatorListQueryMode() == NavigatorListQueryMode.REQUIRED_SCOPE)
                    || binding.source() == PageContextSource.RESOLVED_SELECTION;
            if (resolvedSelection != null && value == null) {
                criteria.isNull(binding.targetKey());
                continue;
            }
            if (value == null) {
                if (required) throw missingScope(binding);
                continue;
            }
            criteria.eq(binding.targetKey(), value);
        }
        return criteria;
    }

    /** Returns whether the current request carries a non-null explicit page-context value. */
    public static boolean hasContextValue(String key) {
        if (key == null || key.isBlank()) return false;
        return requestContextValues().get(key) != null;
    }

    /** Required record scope is the mutation/view counterpart of a required navigator list scope. */
    public static List<PageContextBindingDefinition> recordScopeBindings(List<PageContextBindingDefinition> bindings) {
        if (bindings == null || bindings.isEmpty()) return List.of();
        return bindings.stream()
                .filter(binding -> binding.target() == PageContextTarget.LIST_QUERY)
                .filter(binding -> binding.source() == PageContextSource.RESOLVED_SELECTION
                        || binding.source() == PageContextSource.NAVIGATOR
                        && binding.navigatorListQueryMode() == NavigatorListQueryMode.REQUIRED_SCOPE)
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

    /**
     * Applies a required scope that may include a server-resolved opaque selection.
     *
     * <p>The overloaded form exists for standard CRUD operations only. It keeps the older
     * navigator header behaviour intact while making a selection resolver run with the precise
     * operation being performed. A present {@code null} remains a value and is stamped rather
     * than treated as an absent context.</p>
     */
    public static <T> T applyForCreate(T record, List<PageContextBindingDefinition> bindings,
                                       String moduleAlias, PlatformAction action,
                                       PageSelectionContextResolverRegistry selectionResolvers) {
        Map<String, PageContextValue> scope = requiredScopeValues(bindings, moduleAlias, action, selectionResolvers);
        if (scope.isEmpty()) return record;
        BeanWrapper properties = properties(record);
        scope.forEach((field, value) -> {
            if (!properties.isWritableProperty(field)) {
                throw new IllegalArgumentException("page record scope field is not writable: " + field);
            }
            properties.setPropertyValue(field, value.value());
        });
        return record;
    }

    /** Verifies a record against a required scope, re-resolving opaque selections for this action. */
    public static void requireRecordInScope(Object record, List<PageContextBindingDefinition> bindings,
                                            String moduleAlias, PlatformAction action,
                                            PageSelectionContextResolverRegistry selectionResolvers) {
        Map<String, PageContextValue> scope = requiredScopeValues(bindings, moduleAlias, action, selectionResolvers);
        if (scope.isEmpty()) return;
        BeanWrapper properties = properties(record);
        scope.forEach((field, expected) -> {
            if (!properties.isReadableProperty(field)) {
                throw new IllegalArgumentException("page record scope field is not readable: " + field);
            }
            if (!Objects.equals(properties.getPropertyValue(field), expected.value())) {
                throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                        "Record does not belong to the current page scope: " + field);
            }
        });
    }

    /** Resolves mutation bindings without ever treating a browser value as an authority. */
    static PageContextValue requiredMutationValue(PageContextBindingDefinition binding,
                                                  String moduleAlias, PlatformAction action,
                                                  PageSelectionContextResolverRegistry selectionResolvers) {
        if (binding == null || binding.target() != PageContextTarget.MUTATION_CONSTRAINT) {
            throw new IllegalArgumentException("page mutation context binding is required");
        }
        return requiredAuthoritativeValue(binding, moduleAlias, action, selectionResolvers);
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

    private static Map<String, PageContextValue> requiredScopeValues(List<PageContextBindingDefinition> bindings,
                                                                       String moduleAlias, PlatformAction action,
                                                                       PageSelectionContextResolverRegistry selectionResolvers) {
        if (bindings == null || bindings.isEmpty()) return Map.of();
        Map<String, PageContextValue> values = new LinkedHashMap<>();
        for (PageContextBindingDefinition binding : bindings) {
            PageContextValue value = requiredAuthoritativeValue(binding, moduleAlias, action, selectionResolvers);
            if (!value.present()) continue;
            PageContextValue previous = values.putIfAbsent(binding.targetKey(), value);
            if (previous != null && !Objects.equals(previous, value)) {
                throw new IllegalStateException("conflicting page scope values for field: " + binding.targetKey());
            }
        }
        return Map.copyOf(values);
    }

    private static PageContextValue requiredAuthoritativeValue(PageContextBindingDefinition binding,
                                                               String moduleAlias, PlatformAction action,
                                                               PageSelectionContextResolverRegistry selectionResolvers) {
        if (binding.source() == PageContextSource.RESOLVED_SELECTION) {
            return PageSelectionContextRuntime.requiredValue(binding, moduleAlias, action, selectionResolvers);
        }
        if (binding.source() == PageContextSource.NAVIGATOR) {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) {
                // Direct Java callers do not carry a browser workspace. Preserve the legacy
                // contract: navigator scope applies only to an actual HTTP page request.
                return PageContextValue.absent();
            }
            Object value = contextValue(binding, requestContextValues());
            if (value == null) throw missingScope(binding);
            return PageContextValue.of(value);
        }
        Object value = PageContextServerValueResolver.resolve(binding).orElseThrow(() ->
                new IllegalStateException("cannot resolve page context: " + binding.sourceKey()));
        return PageContextValue.of(value);
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
