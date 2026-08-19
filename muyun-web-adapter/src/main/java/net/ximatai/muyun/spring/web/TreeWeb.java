package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

public interface TreeWeb<T extends EntityContract & TreeCapable, S extends TreeAbility<T>>
        extends TreeWebProjectionPolicy<T, S> {
    @PostMapping("/sort/{id}")
    @ActionEndpoint(PlatformAction.SORT)
    @StandardMutation(StandardMutationKind.SORT)
    default int sort(HttpServletRequest httpRequest,
                     @PathVariable String id,
                     @RequestBody(required = false) TreeSortWebRequest request) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            TreeSortWebRequest normalized = request == null ? new TreeSortWebRequest(null, null, null) : request;
            requireSortInput(normalized);
            requireTreeSortScope(httpRequest, id, normalized);
            return StaticStandardMutationSupport.sorted(this, () -> {
                moveTree(httpRequest, id, normalized);
                return 1;
            });
        }));
    }

    @GetMapping("/tree")
    @ActionEndpoint(PlatformAction.TREE)
    default WebListResponse<?> tree(HttpServletRequest request,
                                    @RequestParam(defaultValue = "false") boolean flat) {
        return webScope(() -> {
            List<T> roots = treeChildren(request, TreeAbility.ROOT_ID);
            if (flat) {
                List<T> rows = new ArrayList<>();
                for (T root : roots) {
                    rows.add(root);
                    appendDescendants(request, root.getId(), rows);
                }
                return new WebListResponse<>(WebOutputSupport.records(service(), rows, FieldOutputContext.VIEW));
            }
            return new WebListResponse<>(roots.stream().map(root -> treeNode(request, root)).toList());
        });
    }

    /**
     * Resolves a tree with descriptor-owned external query values, for example an upstream
     * navigator selection. Tree scope policies consume these values on the server.
     */
    @PostMapping("/tree/query")
    @ActionEndpoint(PlatformAction.TREE)
    default WebListResponse<?> treeQuery(HttpServletRequest request,
                                         @RequestBody(required = false) WebQueryRequest query) {
        TreeWebQuerySupport.bind(request, query);
        return tree(request, false);
    }

    @GetMapping("/tree/{id}")
    @ActionEndpoint(PlatformAction.TREE)
    default WebListResponse<?> tree(HttpServletRequest request,
                                    @PathVariable String id,
                                    @RequestParam(defaultValue = "false") boolean flat,
                                    @RequestParam(defaultValue = "true") boolean includeSelf) {
        return webScope(() -> {
            T root = treeSelect(request, id);
            if (root == null) {
                return new WebListResponse<>(List.of());
            }
            if (!flat) {
                if (includeSelf) {
                    return new WebListResponse<>(List.of(treeNode(request, root)));
                }
                return new WebListResponse<>(treeChildren(request, root.getId()).stream()
                        .map(child -> treeNode(request, child))
                        .toList());
            }
            List<T> rows = new ArrayList<>();
            if (includeSelf) {
                rows.add(root);
            }
            appendDescendants(request, root.getId(), rows);
            return new WebListResponse<>(WebOutputSupport.records(service(), rows, FieldOutputContext.VIEW));
        });
    }

    private void appendDescendants(HttpServletRequest request, String parentId, List<T> rows) {
        for (T child : treeChildren(request, parentId)) {
            rows.add(child);
            appendDescendants(request, child.getId(), rows);
        }
    }

    private WebTreeNode<T> treeNode(HttpServletRequest request, T record) {
        return new WebTreeNode<>(WebOutputSupport.record(service(), record, FieldOutputContext.VIEW),
                treeChildren(request, record.getId()).stream().map(child -> treeNode(request, child)).toList());
    }

    private void requireSortInput(TreeSortWebRequest request) {
        if ((request.previousId() == null || request.previousId().isBlank())
                && (request.nextId() == null || request.nextId().isBlank())
                && (request.parentId() == null || request.parentId().isBlank())) {
            throw new IllegalArgumentException("tree sort requires previousId, nextId, or parentId");
        }
    }

}
