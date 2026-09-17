package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.openapi.OpenApi31Projector;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Shared handler for exact OpenAPI mappings declared by {@link StaticModuleOpenApi}. */
@Component
public class StaticModuleOpenApiEndpoint {
    private static final String OPEN_API_SUFFIX = "/openapi";
    private final StaticModuleOpenApiGenerator generator;
    private final Map<String, String> moduleAliasByPath = new ConcurrentHashMap<>();

    public StaticModuleOpenApiEndpoint(StaticModuleOpenApiGenerator generator) {
        this.generator = generator;
    }

    @ActionEndpoint(PlatformAction.VIEW)
    @ModuleDiscoveryEndpoint
    @ResponseBody
    public Map<String, Object> openApi(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        String moduleAlias = moduleAliasByPath.get(requestPath);
        if (moduleAlias == null) throw new IllegalStateException("unknown static module OpenAPI mapping: " + requestPath);
        return OpenApi31Projector.project(generator.generate(moduleAlias));
    }

    public void register(String moduleAlias, String path) {
        if (!path.endsWith(OPEN_API_SUFFIX)) {
            throw new IllegalArgumentException("static module OpenAPI mapping must end with /openapi: " + path);
        }
        String existing = moduleAliasByPath.putIfAbsent(path, moduleAlias);
        if (existing != null && !existing.equals(moduleAlias)) {
            throw new IllegalStateException("static module OpenAPI mapping already belongs to " + existing + ": " + path);
        }
    }
}
