package net.ximatai.muyun.spring.platform.web.endpoint;

import net.ximatai.muyun.spring.web.endpoint.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.web.RecordActionWebRequest;
import net.ximatai.muyun.spring.web.BusinessMutation;
import net.ximatai.muyun.spring.web.SortWebRequest;
import net.ximatai.muyun.spring.platform.web.StaticAbilityOperationRuntime;
import net.ximatai.muyun.spring.web.TreeSortWebRequest;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.lang.reflect.Method;

/** Single Spring MVC entry point for every descriptor-driven platform endpoint. */
@ResponseBody
@BusinessMutation
public final class PlatformWebOperationDispatcher {
    private static final Method DISPATCH_METHOD = dispatchMethod();

    private final RegisteredWebEndpointCatalog catalog;
    private final ObjectMapper objectMapper;
    private final StaticAbilityOperationRuntime runtime;

    public PlatformWebOperationDispatcher(RegisteredWebEndpointCatalog catalog,
                                          ObjectMapper objectMapper,
                                          StaticAbilityOperationRuntime runtime) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.runtime = runtime;
    }

    public Object dispatch(HttpServletRequest request) throws IOException {
        RegisteredWebEndpoint endpoint = catalog.require(request, this, DISPATCH_METHOD);
        return runtime.execute(endpoint, request, readBody(endpoint, request));
    }

    public Method handlerMethod() {
        return DISPATCH_METHOD;
    }

    private Object readBody(RegisteredWebEndpoint endpoint, HttpServletRequest request) throws IOException {
        Class<?> bodyType = switch (endpoint.definition().action()) {
            case ENABLE, DISABLE -> RecordActionWebRequest.class;
            case SORT -> endpoint.staticTarget() != null
                    && endpoint.staticTarget().service() instanceof TreeAbility<?>
                    ? TreeSortWebRequest.class
                    : SortWebRequest.class;
            case RECYCLE_BIN_QUERY -> "query".equals(endpoint.definition().operationCode())
                    ? WebQueryRequest.class : null;
            case TREE -> "treeQuery".equals(endpoint.definition().operationCode())
                    ? WebQueryRequest.class : null;
            default -> null;
        };
        if (bodyType == null) return null;
        byte[] content = request.getInputStream().readAllBytes();
        return content.length == 0 ? null : objectMapper.readValue(content, bodyType);
    }

    private static Method dispatchMethod() {
        try {
            return PlatformWebOperationDispatcher.class.getMethod("dispatch", HttpServletRequest.class);
        } catch (NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
