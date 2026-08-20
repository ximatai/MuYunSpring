package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.web.RecordActionWebRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.StandardMutation;
import net.ximatai.muyun.spring.web.StandardMutationKind;
import net.ximatai.muyun.spring.web.StandardMutationResultSupport;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Map;

/** Standard HTTP delivery for one-level relations; the endpoint shape never belongs to a business descriptor. */
public interface ManagedDetailRelationWeb<P extends EntityContract, S extends CrudAbility<P>> extends CrudWeb<P, S> {
    default ManagedDetailRelationGateway managedDetailRelationGateway() { return null; }

    private ManagedDetailRelationGateway requireManagedDetailRelationGateway() {
        ManagedDetailRelationGateway gateway = managedDetailRelationGateway();
        if (gateway == null) throw new IllegalStateException("managed detail relation gateway is not installed: " + webScopeName());
        return gateway;
    }

    private <R> R managedRelationWireResponse(String relationCode, R response) {
        StandardModuleWebRuntime runtime = standardModuleWebRuntime();
        if (runtime != null) runtime.markRelationWireResponse(webScopeName(), relationCode);
        return response;
    }

    @PostMapping("/view/{parentId}/relations/{relationCode}/query")
    @ActionEndpoint(PlatformAction.VIEW)
    default WebPageResponse<?> queryManagedDetailRelation(@PathVariable String parentId, @PathVariable String relationCode,
                                                          @RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> managedRelationWireResponse(relationCode,
                requireManagedDetailRelationGateway().query(webScopeName(), service(), parentId, relationCode, request)));
    }

    @PostMapping("/view/{parentId}/relations/{relationCode}/insert")
    @ActionEndpoint(PlatformAction.UPDATE)
    @StandardMutation(StandardMutationKind.CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    default Object insertManagedDetailRelation(@PathVariable String parentId, @PathVariable String relationCode,
                                               @RequestBody Map<String, Object> payload) {
        return webScope(() -> {
            Object saved = requireManagedDetailRelationGateway().insert(webScopeName(), service(), parentId, relationCode, payload);
            if (saved instanceof EntityContract entity) {
                StandardMutationResultSupport.resourceCreated(webScopeName(), relationCode, parentId, entity.getId());
            }
            return managedRelationWireResponse(relationCode, saved);
        });
    }

    @PostMapping("/view/{parentId}/relations/{relationCode}/update/{childId}")
    @ActionEndpoint(PlatformAction.UPDATE)
    @StandardMutation(StandardMutationKind.UPDATE)
    @Transactional
    default Object updateManagedDetailRelation(@PathVariable String parentId, @PathVariable String relationCode,
                                               @PathVariable String childId, @RequestBody Map<String, Object> payload) {
        return webScope(() -> {
            Object saved = requireManagedDetailRelationGateway().update(webScopeName(), service(), parentId, relationCode, childId, payload);
            StandardMutationResultSupport.resourceUpdated(webScopeName(), relationCode, parentId, childId);
            return managedRelationWireResponse(relationCode, saved);
        });
    }

    @PostMapping("/view/{parentId}/relations/{relationCode}/delete/{childId}")
    @ActionEndpoint(PlatformAction.UPDATE)
    @StandardMutation(StandardMutationKind.DELETE)
    @Transactional
    default int deleteManagedDetailRelation(@PathVariable String parentId, @PathVariable String relationCode,
                                            @PathVariable String childId, @RequestBody(required = false) RecordActionWebRequest request) {
        return webScope(() -> StandardMutationResultSupport.resourceDeleted(
                webScopeName(), relationCode, parentId, childId,
                () -> requireManagedDetailRelationGateway().delete(webScopeName(), service(), parentId,
                        relationCode, childId, request)));
    }
}
