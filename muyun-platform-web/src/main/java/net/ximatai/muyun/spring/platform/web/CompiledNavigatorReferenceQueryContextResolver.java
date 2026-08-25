package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.query.QueryCompiler;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.web.NavigatorReferenceQueryContextResolver;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Compiles host-owned navigator context for static REFERENCE transports.
 *
 * <p>The adapter invokes this through its minimal SPI, so static controllers in any {@code *-web}
 * module keep their dependency direction. The immutable host execution plan owns bindings while
 * the resolved host descriptor verifies that the caller may address the requested navigator level.</p>
 */
@Component
public class CompiledNavigatorReferenceQueryContextResolver implements NavigatorReferenceQueryContextResolver {
    private final ModuleExecutionPlanCatalog executionPlans;
    private final PlatformModuleRuntimeContextService runtimeContexts;

    public CompiledNavigatorReferenceQueryContextResolver(ModuleExecutionPlanCatalog executionPlans,
                                                          PlatformModuleRuntimeContextService runtimeContexts) {
        this.executionPlans = executionPlans;
        this.runtimeContexts = runtimeContexts;
    }

    @Override
    public Criteria queryCriteria(String sourceModuleAlias, CrudAbility<?> sourceService, WebQueryRequest request,
                                  Supplier<Criteria> fallbackCriteria) {
        WebQueryRequest normalized = normalizeRequest(sourceModuleAlias, request);
        Criteria sourceCriteria = executionPlans.find(sourceModuleAlias)
                .map(plan -> new QueryCompiler(plan.queryDescriptor()).criteria(WebQueryRequests.from(normalized)))
                .orElseGet(() -> fallbackCriteria == null ? Criteria.of() : fallbackCriteria.get());
        return and(sourceCriteria, navigatorCriteria(sourceModuleAlias, normalized));
    }

    @Override
    public WebQueryRequest normalizeRequest(String sourceModuleAlias, WebQueryRequest request) {
        if (request == null || !hasNavigatorReferenceContext(request)) return request;
        List<PageContextBindingDefinition> bindings = navigatorBindings(sourceModuleAlias, request);
        if (bindings.isEmpty()) return request;
        Map<String, Object> values = new LinkedHashMap<>(request.externalQueryValues());
        for (PageContextBindingDefinition binding : bindings) {
            Object value = PageContextServerValueResolver.resolve(binding).orElseGet(() ->
                    request.externalQueryValues().get(binding.targetKey()));
            if (value == null) {
                values.remove(binding.targetKey());
            } else {
                values.put(binding.targetKey(), value);
            }
        }
        return request.withExternalQueryValues(values);
    }

    private Criteria navigatorCriteria(String sourceModuleAlias, WebQueryRequest request) {
        if (request == null || !hasNavigatorReferenceContext(request)) return Criteria.of();
        Criteria criteria = Criteria.of();
        for (PageContextBindingDefinition binding : navigatorBindings(sourceModuleAlias, request)) {
            Object selectedValue = PageContextServerValueResolver.resolve(binding).orElseGet(() ->
                    request.externalQueryValues().get(binding.targetKey()));
            if (selectedValue != null) criteria.eq(binding.targetKey(), selectedValue);
        }
        return criteria;
    }

    private List<PageContextBindingDefinition> navigatorBindings(String sourceModuleAlias, WebQueryRequest request) {
        if (!hasNavigatorReferenceContext(request)) return List.of();
        ModuleExecutionPlan hostPlan = executionPlans.find(request.navigatorHostModuleAlias()).orElseThrow(() ->
                PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                        "Navigator host module is not executable: " + request.navigatorHostModuleAlias()));
        ResolvedModuleUiDescriptor hostDescriptor = runtimeContexts.context(request.navigatorHostModuleAlias()).uiDescriptor();
        ResolvedPageNavigatorDescriptor navigator = hostDescriptor == null || hostDescriptor.page() == null
                ? null : hostDescriptor.page().navigator();
        boolean sourceMatches = navigator != null && navigator.levels().stream().anyMatch(level ->
                request.navigatorTargetLevelKey().equals(level.key())
                        && sourceModuleAlias.equals(level.sourceModuleAlias()));
        if (!sourceMatches) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                    "Navigator request target does not match source module");
        }
        return hostPlan.pageContextBindings().stream()
                .filter(binding -> binding.target() == PageContextTarget.NAVIGATOR_QUERY)
                .filter(binding -> request.navigatorTargetLevelKey().equals(binding.targetNavigatorLevelKey()))
                .toList();
    }

    private boolean hasNavigatorReferenceContext(WebQueryRequest request) {
        boolean hasHost = hasText(request.navigatorHostModuleAlias());
        boolean hasLevel = hasText(request.navigatorTargetLevelKey());
        if (hasHost != hasLevel) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                    "Navigator reference requires both host module alias and target level key");
        }
        return hasHost;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Criteria and(Criteria first, Criteria second) {
        if (first == null || first.isEmpty()) return second == null ? Criteria.of() : second;
        if (second == null || second.isEmpty()) return first;
        Criteria result = Criteria.of();
        result.andGroup(first.getRoot());
        result.andGroup(second.getRoot());
        return result;
    }
}
