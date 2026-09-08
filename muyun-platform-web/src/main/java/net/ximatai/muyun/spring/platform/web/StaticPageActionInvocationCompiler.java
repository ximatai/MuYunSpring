package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.web.FormActionWeb;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles only the small input protocol that the standard page buttons can actually supply. */
final class StaticPageActionInvocationCompiler {
    private StaticPageActionInvocationCompiler() { }

    static Map<String, Map<PageActionAnchor, PageActionInvocation>> compile(Class<?> controller, String moduleAlias,
            List<StaticModuleActionDefinition> actions, java.util.function.Function<String, String> actionCodeOf) {
        Map<String, Map<PageActionAnchor, PageActionInvocation>> result = new LinkedHashMap<>();
        java.util.Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(controller)).forEach(method -> {
            var endpoint = AnnotatedElementUtils.findMergedAnnotation(method, CustomActionEndpoint.class);
            if (endpoint == null || !endpoint.pageInvocable()) return;
            if (!endpoint.actionCodePathVariable().isBlank()) throw invalid(method, "variable action is not a button declaration");
            var mapping = mapping(method);
            String path = httpPath(controller, moduleAlias, mapping, method);
            PageActionAnchor anchor;
            if (method.getParameterCount() == 0 && !path.contains("{")) {
                if (endpoint.level() != PlatformActionLevel.LIST && endpoint.level() != PlatformActionLevel.ANY
                        && endpoint.level() != PlatformActionLevel.DEFAULT) throw invalid(method, "page action requires LIST scope");
                anchor = PageActionAnchor.PAGE;
            } else if (method.getParameterCount() == 1) {
                var parameter = method.getParameters()[0];
                var variable = parameter.getAnnotation(PathVariable.class);
                String name = variable == null ? "" : !variable.name().isBlank() ? variable.name()
                        : !variable.value().isBlank() ? variable.value() : parameter.getName();
                if (variable == null || parameter.getType() != String.class || !name.equals(endpoint.recordIdPathVariable())
                        || !path.contains("{" + name + "}") || !path.replace("{" + name + "}", "").matches("[^{}]*")
                        || path.indexOf("{" + name + "}") != path.lastIndexOf("{" + name + "}")) {
                    throw invalid(method, "detail action requires its single String record-id path variable");
                }
                if (endpoint.level() != PlatformActionLevel.RECORD && endpoint.level() != PlatformActionLevel.ANY
                        && endpoint.level() != PlatformActionLevel.DEFAULT) throw invalid(method, "detail action requires RECORD scope");
                path = path.replace("{" + name + "}", "{recordId}");
                anchor = PageActionAnchor.DETAIL;
            } else throw invalid(method, "page buttons do not supply body, query or additional inputs");
            String actionCode = actionCodeOf.apply(endpoint.value());
            var bindings = new LinkedHashMap<>(result.getOrDefault(actionCode, Map.of()));
            var invocation = new PageActionInvocation(mapping.method()[0].name(), path, PageActionInvocation.Input.NONE);
            if (bindings.putIfAbsent(anchor, invocation) != null) {
                throw invalid(method, "multiple button mappings for action " + actionCode + " / " + anchor);
            }
            result.put(actionCode, Map.copyOf(bindings));
        });
        if (FormActionWeb.class.isAssignableFrom(controller)) {
            for (var action : actions) {
                if (!action.formSupported()) continue;
                Map<PageActionAnchor, PageActionInvocation> bindings = new LinkedHashMap<>(result.getOrDefault(action.actionCode(), Map.of()));
                Method formMethod = java.util.Arrays.stream(controller.getMethods())
                        .filter(method -> method.getName().equals("formAction") && !method.isBridge()
                                && method.getParameterCount() == 2 && method.getParameterTypes()[0] == String.class
                                && method.getParameterTypes()[1] == net.ximatai.muyun.spring.web.FormActionRequest.class)
                        .findFirst().orElseThrow(() -> new IllegalStateException("FormActionWeb mapping is unavailable: " + controller.getName()));
                var mapping = mapping(formMethod);
                String path = httpPath(controller, moduleAlias, mapping, formMethod);
                String actionVariable = net.ximatai.muyun.spring.common.web.PlatformWebPathRules.ACTION_CODE_PATH;
                if (!path.endsWith("/form-actions/" + actionVariable) || mapping.method()[0] != org.springframework.web.bind.annotation.RequestMethod.POST) {
                    throw invalid(formMethod, "requires the standard FormActionWeb mapping");
                }
                bindings.put(PageActionAnchor.FORM, new PageActionInvocation("POST", path.replace(actionVariable, action.actionCode()), PageActionInvocation.Input.FORM_RECORD));
                result.put(action.actionCode(), Map.copyOf(bindings));
            }
        }
        return Map.copyOf(result);
    }

    private static String httpPath(Class<?> controller, String moduleAlias, RequestMapping methodMapping, Method method) {
        var root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        if (root == null || root.params().length > 0 || root.headers().length > 0 || root.consumes().length > 0
                || root.method().length > 0 && (root.method().length != 1 || root.method()[0] != methodMapping.method()[0])) {
            throw invalid(method, "requires one unconditional module HTTP root");
        }
        String rootPath = path(root, method);
        String moduleRoot = "/" + moduleAlias;
        if (!rootPath.equals(moduleRoot) && !rootPath.startsWith(moduleRoot + "/")) {
            throw invalid(method, "HTTP root must belong to " + moduleRoot);
        }
        return rootPath.replaceAll("/$", "") + path(methodMapping, method);
    }

    private static RequestMapping mapping(Method method) {
        var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null || mapping.method().length != 1 || mapping.params().length > 0
                || mapping.headers().length > 0 || mapping.consumes().length > 0) {
            throw invalid(method, "requires one unconditional HTTP mapping");
        }
        return mapping;
    }

    private static String path(RequestMapping mapping, Method method) {
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        if (paths.length != 1) throw invalid(method, "requires one HTTP path");
        return paths[0].startsWith("/") ? paths[0] : "/" + paths[0];
    }

    private static IllegalStateException invalid(Method method, String reason) {
        return new IllegalStateException("invalid pageInvocable endpoint " + method.toGenericString() + ": " + reason);
    }
}
