package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ActionEndpointContextResolver {
    static final String MODULE_ALIAS_PATH_KEY = "moduleAlias";
    static final String[] RECORD_ID_KEYS = {"id", "recordId"};
    static final String IDS_KEY = "ids";

    private final PlatformModuleActionService moduleActionService;

    public ActionEndpointContextResolver() {
        this(null);
    }

    public ActionEndpointContextResolver(PlatformModuleActionService moduleActionService) {
        this.moduleActionService = moduleActionService;
    }

    public Optional<ActionExecutionContext> resolve(HttpServletRequest request,
                                                    HandlerMethod handlerMethod,
                                                    ActionEndpoint endpoint) {
        PlatformStaticActionContribution contribution = contribution(handlerMethod);
        String moduleAlias = contribution == null
                ? moduleAlias(request, handlerMethod)
                : PlatformStaticActionContributionSupport.targetModule(contribution);
        if (moduleAlias == null || moduleAlias.isBlank()) {
            return Optional.empty();
        }
        String actionCode = contribution == null
                ? endpoint.value().code()
                : PlatformStaticActionContributionSupport.actionCode(contribution, endpoint.value());
        requireActionPublished(moduleAlias, actionCode);
        ActionExecutionPolicy policy = registeredPolicy(moduleAlias, actionCode)
                .orElseGet(() -> contribution == null
                        ? endpoint.value().executionPolicy()
                        : contributionPolicy(contribution, endpoint.value()));
        return Optional.of(ActionExecutionContext.ofPolicy(
                moduleAlias,
                policy,
                recordIds(request),
                CurrentUserContext.currentUser()
        ));
    }

    public Optional<ActionExecutionContext> resolve(HttpServletRequest request,
                                                    HandlerMethod handlerMethod,
                                                    CustomActionEndpoint endpoint) {
        PlatformStaticActionContribution contribution = contribution(handlerMethod);
        String moduleAlias = contribution == null
                ? moduleAlias(request, handlerMethod)
                : PlatformStaticActionContributionSupport.targetModule(contribution);
        if (moduleAlias == null || moduleAlias.isBlank()) {
            return Optional.empty();
        }
        String actionCode = contribution == null
                ? PlatformNameRules.requireActionCode(endpoint.value(), "actionCode")
                : PlatformStaticActionContributionSupport.actionCode(contribution, endpoint.value());
        requireActionPublished(moduleAlias, actionCode);
        ActionExecutionPolicy policy = registeredPolicy(moduleAlias, actionCode)
                .orElseGet(() -> new ActionExecutionPolicy(
                        actionCode,
                        endpoint.level(),
                        endpoint.accessMode(),
                        endpoint.actionAuth(),
                        endpoint.dataAuth(),
                        ActionDefaultGrantPolicy.NONE,
                        null
                ));
        return Optional.of(ActionExecutionContext.ofPolicy(
                moduleAlias,
                policy,
                customRecordIds(request, endpoint),
                CurrentUserContext.currentUser()
        ));
    }

    public ActionExecutionContext resolve(HttpServletRequest request, ResolvedWebEndpoint endpoint) {
        requireActionPublished(endpoint.moduleAlias(), endpoint.executionPolicy().actionCode());
        ActionExecutionPolicy policy = resolvedPolicy(endpoint.moduleAlias(), endpoint.executionPolicy());
        return ActionExecutionContext.ofPolicy(
                endpoint.moduleAlias(),
                policy,
                recordIds(request),
                CurrentUserContext.currentUser()
        );
    }

    /**
     * Resolves a module-scoped action outside an HTTP handler while preserving the same persisted
     * policy override used by the action interceptor.  API catalogs use this to decide whether a
     * module may be described to the current caller.
     */
    public ActionExecutionContext resolveModuleAction(String moduleAlias, net.ximatai.muyun.spring.common.platform.PlatformAction action) {
        requireActionPublished(moduleAlias, action.code());
        return ActionExecutionContext.ofPolicy(
                moduleAlias,
                resolvedPolicy(moduleAlias, action.executionPolicy()),
                Set.of(),
                CurrentUserContext.currentUser()
        );
    }

    /** Resolves a compiled custom action code with the same persisted governance used by HTTP endpoints. */
    public ActionExecutionContext resolveActionCode(String moduleAlias, String actionCode, Set<String> recordIds) {
        String normalized = PlatformNameRules.requireActionCode(actionCode, "actionCode");
        requireActionPublished(moduleAlias, normalized);
        ActionExecutionPolicy fallback = ActionExecutionContext.ofActionCode(
                moduleAlias, normalized, recordIds, CurrentUserContext.currentUser()).actionPolicy();
        return ActionExecutionContext.ofPolicy(moduleAlias, resolvedPolicy(moduleAlias, fallback),
                recordIds, CurrentUserContext.currentUser());
    }

    /** Resolves a complete compiled action definition, then applies the persisted policy override. */
    public ActionExecutionContext resolveAction(String moduleAlias,
                                                StaticModuleActionDefinition action,
                                                Set<String> recordIds) {
        java.util.Objects.requireNonNull(action, "action must not be null");
        requireActionPublished(moduleAlias, action.actionCode());
        ActionExecutionPolicy policy = resolvedPolicy(moduleAlias, action.executionPolicy());
        return ActionExecutionContext.ofPolicy(moduleAlias, policy, recordIds, CurrentUserContext.currentUser());
    }

    /** Resolves a compiled endpoint for projections such as OpenAPI. */
    public ActionExecutionContext resolve(ResolvedWebEndpoint endpoint) {
        requireActionPublished(endpoint.moduleAlias(), endpoint.executionPolicy().actionCode());
        return ActionExecutionContext.ofPolicy(
                endpoint.moduleAlias(),
                resolvedPolicy(endpoint.moduleAlias(), endpoint.executionPolicy()),
                Set.of(),
                CurrentUserContext.currentUser()
        );
    }

    private PlatformStaticActionContribution contribution(HandlerMethod handlerMethod) {
        PlatformStaticActionContribution methodContribution =
                org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                        handlerMethod.getMethod(), PlatformStaticActionContribution.class);
        if (methodContribution != null) {
            return methodContribution;
        }
        return org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                handlerMethod.getBeanType(), PlatformStaticActionContribution.class);
    }

    private ActionExecutionPolicy contributionPolicy(PlatformStaticActionContribution contribution,
                                                     net.ximatai.muyun.spring.common.platform.PlatformAction action) {
        String actionCode = PlatformStaticActionContributionSupport.actionCode(contribution, action);
        String permissionActionCode = PlatformStaticActionContributionSupport.permissionActionCode(contribution, action);
        return new ActionExecutionPolicy(
                actionCode,
                action.level(),
                action.accessMode(),
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy(),
                actionCode.equals(permissionActionCode) ? null : permissionActionCode
        );
    }

    private String moduleAlias(HttpServletRequest request, HandlerMethod handlerMethod) {
        PlatformStaticModule staticModule = handlerMethod.getBeanType().getAnnotation(PlatformStaticModule.class);
        if (staticModule != null && !staticModule.alias().isBlank()) {
            return staticModule.alias();
        }
        PlatformStaticActionScope actionScope = handlerMethod.getBeanType()
                .getAnnotation(PlatformStaticActionScope.class);
        if (actionScope != null && !actionScope.module().isBlank()) {
            return actionScope.module();
        }
        PlatformStaticWebProjection projection = handlerMethod.getBeanType()
                .getAnnotation(PlatformStaticWebProjection.class);
        if (projection != null && !projection.module().isBlank()) {
            return projection.module();
        }
        PlatformStaticActionDeclaration declaration = handlerMethod.getBeanType()
                .getAnnotation(PlatformStaticActionDeclaration.class);
        if (declaration != null && !declaration.module().isBlank()) {
            return declaration.module();
        }
        String pathModuleAlias = pathVariable(request, MODULE_ALIAS_PATH_KEY);
        if (pathModuleAlias != null && !pathModuleAlias.isBlank()) {
            return pathModuleAlias;
        }
        Object bean = handlerMethod.getBean();
        if (bean instanceof ScopedWeb<?> scopedWeb) {
            return scopedWeb.webScopeName();
        }
        return null;
    }

    private Optional<ActionExecutionPolicy> registeredPolicy(String moduleAlias, String actionCode) {
        if (moduleActionService == null) {
            return Optional.empty();
        }
        PlatformModuleAction action = moduleActionService.findByModuleAliasAndActionCode(moduleAlias, actionCode);
        if (action == null || Boolean.FALSE.equals(action.getEnabled())) {
            return Optional.empty();
        }
        return Optional.of(toPolicy(action));
    }

    /**
     * A disabled persisted action is no longer a published module operation.  MVC mappings can
     * remain registered until the next application restart, but they must not fall back to the
     * controller's default policy and become callable through a stale URL.
     */
    public void requireActionPublished(String moduleAlias, String actionCode) {
        if (moduleActionService == null) {
            return;
        }
        PlatformModuleAction action = moduleActionService.findByModuleAliasAndActionCode(moduleAlias, actionCode);
        if (action != null && Boolean.FALSE.equals(action.getEnabled())) {
            throw new PlatformException(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404,
                    "module action is not published: " + moduleAlias + "." + actionCode);
        }
    }

    private ActionExecutionPolicy resolvedPolicy(String moduleAlias, ActionExecutionPolicy fallback) {
        return registeredPolicy(moduleAlias, fallback.actionCode()).orElse(fallback);
    }

    private ActionExecutionPolicy toPolicy(PlatformModuleAction action) {
        String actionCode = PlatformNameRules.requireActionCode(action.getActionCode(), "actionCode");
        String permissionActionCode = action.getPermissionActionCode();
        String inheritActionCode = permissionActionCode == null || permissionActionCode.isBlank()
                || permissionActionCode.equals(actionCode)
                ? null
                : PlatformNameRules.requireActionCode(permissionActionCode, "permissionActionCode");
        return new ActionExecutionPolicy(
                actionCode,
                toPlatformLevel(action.getActionLevel()),
                action.effectiveAccessMode() == null
                        ? ActionAccessMode.AUTH_REQUIRED
                        : ActionAccessMode.valueOf(action.effectiveAccessMode().name()),
                action.effectiveActionAuth(),
                action.effectiveDataAuth(),
                action.effectiveDefaultGrantPolicy(),
                inheritActionCode
        );
    }

    private PlatformActionLevel toPlatformLevel(EntityActionLevel level) {
        if (level == null) {
            return PlatformActionLevel.ANY;
        }
        return switch (level) {
            case LIST -> PlatformActionLevel.LIST;
            case RECORD -> PlatformActionLevel.RECORD;
            case BATCH -> PlatformActionLevel.BATCH;
            case ANY -> PlatformActionLevel.ANY;
        };
    }

    private Set<String> recordIds(HttpServletRequest request) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String key : RECORD_ID_KEYS) {
            collect(ids, pathVariable(request, key));
            collect(ids, request.getParameterValues(key));
        }
        collect(ids, pathVariable(request, IDS_KEY));
        collect(ids, request.getParameterValues(IDS_KEY));
        return Set.copyOf(ids);
    }

    private Set<String> customRecordIds(HttpServletRequest request, CustomActionEndpoint endpoint) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(recordIds(request));
        String key = endpoint.recordIdPathVariable();
        if (key != null && !key.isBlank()) {
            collect(ids, pathVariable(request, key));
        }
        return Set.copyOf(ids);
    }

    private String pathVariable(HttpServletRequest request, String key) {
        Object value = pathVariables(request).get(key);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> pathVariables(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables)) {
            return Map.of();
        }
        return variables.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toString(),
                        entry -> entry.getValue().toString()
                ));
    }

    private void collect(Set<String> ids, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .forEach(ids::add);
    }

    private void collect(Set<String> ids, String[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        Arrays.stream(values).forEach(value -> collect(ids, value));
    }
}
