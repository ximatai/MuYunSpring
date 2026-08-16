package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/** Read-only tree transport for navigator sources, governed by {@link PlatformAction#REFERENCE}. */
public interface NavigatorReferenceTreeWeb<T extends EntityContract & TreeCapable, S extends TreeAbility<T>>
        extends TreeWebProjectionPolicy<T, S> {
    @PostMapping("/navigator/reference/tree/query")
    @ActionEndpoint(PlatformAction.REFERENCE)
    default WebListResponse<?> navigatorReferenceTreeQuery(HttpServletRequest request,
                                                           @RequestBody(required = false) WebQueryRequest query) {
        TreeWebQuerySupport.bind(request, query);
        return webScope(() -> new WebListResponse<>(referenceChildren(request, TreeAbility.ROOT_ID).stream()
                .map(record -> referenceNode(request, record)).toList()));
    }

    private List<T> referenceChildren(HttpServletRequest request, String parentId) {
        return treeChildrenForAction(request, PlatformAction.REFERENCE, parentId);
    }

    private WebTreeNode<T> referenceNode(HttpServletRequest request, T record) {
        return new WebTreeNode<>(WebOutputSupport.record(service(), record, FieldOutputContext.VIEW),
                referenceChildren(request, record.getId()).stream().map(child -> referenceNode(request, child)).toList());
    }
}
