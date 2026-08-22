package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.web.TenantRequestScope;
import net.ximatai.muyun.spring.web.WebReferenceResolveRequest;
import net.ximatai.muyun.spring.web.WebReferenceResolveResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Isolated static adapter; deliberately does not participate in CrudWeb routing. */
@RestController
@RequestMapping("/platform.module/{moduleAlias:[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+}/references")
public class StaticReferenceResolveWebController {
    private final StaticReferenceResolveFacade facade;
    private final TenantRequestScope tenantRequestScope;

    public StaticReferenceResolveWebController(StaticReferenceResolveFacade facade,
                                               TenantRequestScope tenantRequestScope) {
        this.facade = facade;
        this.tenantRequestScope = tenantRequestScope;
    }

    @PostMapping("/{fieldName}/resolve")
    @ActionEndpoint(PlatformAction.REFERENCE)
    public WebReferenceResolveResponse resolve(@PathVariable String moduleAlias,
                                               @PathVariable String fieldName,
                                               @RequestBody(required = false) WebReferenceResolveRequest request) {
        if (!TenantContext.isSystem()) {
            tenantRequestScope.requireActiveTenant(moduleAlias);
        }
        return facade.resolve(moduleAlias, fieldName, request);
    }
}
