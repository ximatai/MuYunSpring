package net.ximatai.muyun.spring.platform.web.endpoint;

import net.ximatai.muyun.spring.web.endpoint.*;

import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;
import net.ximatai.muyun.spring.platform.web.PlatformStaticActionContribution;
import net.ximatai.muyun.spring.platform.web.PlatformStaticActionContributionSupport;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.ArrayList;
import java.util.List;

/** Compiles semantic operations into physical static HTTP endpoint projections. */
final class StaticWebEndpointProjectionCompiler {
    List<WebEndpointProjection> compile(String moduleAlias,
                                        List<String> basePaths,
                                        List<PlatformOperationDefinition> operations,
                                        PlatformStaticActionContribution contribution,
                                        String endpointIdNamespace) {
        List<WebEndpointProjection> projections = new ArrayList<>();
        for (PlatformOperationDefinition operation : operations) {
            WebShape shape = shape(operation);
            String abilityCode = contribution == null ? operation.abilityCode()
                    : contribution.resource() + "." + operation.abilityCode();
            ActionExecutionPolicy policy = contribution == null ? operation.action().executionPolicy()
                    : contributionPolicy(contribution, operation);
            for (int index = 0; index < basePaths.size(); index++) {
                String path = join(basePaths.get(index), shape.path());
                String endpointId = endpointId(moduleAlias + "." + abilityCode + "." + operation.operationCode(),
                        endpointIdNamespace, path, index);
                projections.add(new WebEndpointProjection(operation, moduleAlias, abilityCode, policy,
                        shape.method(), endpointId, path));
            }
        }
        return List.copyOf(projections);
    }

    private WebShape shape(PlatformOperationDefinition operation) {
        var capabilityProjection = CapabilityModuleRegistry.defaultRegistry().actionOwner(operation.action())
                .flatMap(contribution -> contribution.endpointProjection(operation.action()));
        if (capabilityProjection.isPresent()) {
            var projection = capabilityProjection.get();
            if (!projection.operationCode().equals(operation.operationCode())) {
                throw new IllegalArgumentException("ENABLE endpoint operation does not match capability contract: "
                        + operation);
            }
            return new WebShape(RequestMethod.valueOf(projection.httpMethod()), projection.path());
        }
        return switch (operation.operationCode()) {
            case "tree" -> new WebShape(RequestMethod.GET, "/tree");
            case "treeQuery" -> new WebShape(RequestMethod.POST, "/tree/query");
            case "subtree" -> new WebShape(RequestMethod.GET, "/tree/{id}");
            case "sort" -> new WebShape(RequestMethod.POST, "/sort/{id}");
            case "query" -> new WebShape(RequestMethod.POST, "/recycle-bin/query");
            case "view" -> new WebShape(RequestMethod.GET, "/recycle-bin/view/{id}");
            case "restore" -> new WebShape(RequestMethod.POST, "/recycle-bin/{sourceDeleteOperationId}/restore");
            case "purge" -> new WebShape(RequestMethod.POST, "/recycle-bin/{sourceDeleteOperationId}/purge");
            default -> throw new IllegalArgumentException("unsupported static standard operation: " + operation);
        };
    }

    private ActionExecutionPolicy contributionPolicy(PlatformStaticActionContribution contribution,
                                                      PlatformOperationDefinition operation) {
        String actionCode = PlatformStaticActionContributionSupport.actionCode(contribution, operation.action());
        String permissionActionCode = PlatformStaticActionContributionSupport.permissionActionCode(contribution,
                operation.action());
        return new ActionExecutionPolicy(actionCode, operation.action().level(), operation.action().accessMode(),
                operation.action().actionAuth(), operation.action().dataAuth(), operation.action().defaultGrantPolicy(),
                actionCode.equals(permissionActionCode) ? null : permissionActionCode);
    }

    private String endpointId(String templateId, String namespace, String path, int index) {
        return namespace.isBlank() ? (index == 0 ? templateId : templateId + "@" + index)
                : templateId + namespace + "." + Integer.toUnsignedString(path.hashCode(), 36);
    }

    private String join(String basePath, String endpointPath) {
        return normalizePath(basePath) + normalizePath(endpointPath);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) return "";
        String normalized = path.trim();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private record WebShape(RequestMethod method, String path) {
    }
}
