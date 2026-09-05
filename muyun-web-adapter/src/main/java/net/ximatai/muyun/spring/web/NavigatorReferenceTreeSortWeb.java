package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Explicit opt-in transport for safely sorting a scoped navigator reference tree. */
public interface NavigatorReferenceTreeSortWeb<T extends EntityContract & TreeCapable, S extends TreeAbility<T>>
        extends NavigatorReferenceTreeWeb<T, S> {
    @PostMapping("/navigator/reference/tree/sort/{id}")
    @ActionEndpoint(PlatformAction.SORT)
    @StandardMutation(StandardMutationKind.SORT)
    default int navigatorReferenceTreeSort(
            HttpServletRequest request,
            @PathVariable String id,
            @RequestBody NavigatorReferenceTreeSortRequest command) {
        if (command == null || command.sort() == null) {
            throw new IllegalArgumentException("navigator reference tree sort requires a sort command");
        }
        TreeSortWebRequest sort = command.sort();
        if ((sort.previousId() == null || sort.previousId().isBlank())
                && (sort.nextId() == null || sort.nextId().isBlank())
                && (sort.parentId() == null || sort.parentId().isBlank())) {
            throw new IllegalArgumentException("tree sort requires previousId, nextId, or parentId");
        }
        NavigatorReferenceQueryContextResolver resolver = navigatorReferenceQueryContextResolver();
        TreeWebQuerySupport.bind(request, resolver == null || command.query() == null
                ? command.query()
                : resolver.normalizeRequest(webScopeName(), command.query()));
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            requireTreeSortScope(request, id, sort);
            return StaticStandardMutationSupport.sorted(this, () -> {
                moveTree(request, id, sort);
                return 1;
            });
        }));
    }
}
