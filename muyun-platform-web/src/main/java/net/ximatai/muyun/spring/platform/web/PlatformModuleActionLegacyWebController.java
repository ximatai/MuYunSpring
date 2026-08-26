package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.web.NestedEnabledSortableCrudWebSupport;
import net.ximatai.muyun.spring.web.RecordActionWebRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compatibility projection for callers which still address module actions through their parent
 * resource path. New pages must use {@code /platform.module_action} with page navigator context.
 */
@RestController
@PlatformStaticWebProjection(module = PlatformModuleActionService.MODULE_ALIAS)
@RequestMapping("/platform.module/{moduleAlias}/actions")
public class PlatformModuleActionLegacyWebController
        extends NestedEnabledSortableCrudWebSupport<PlatformModuleAction, PlatformModuleActionService> {

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        criteria.eq("moduleAlias", moduleAlias(request));
    }

    @Override
    protected void bindScope(PlatformModuleAction record, HttpServletRequest request) {
        record.setModuleAlias(moduleAlias(request));
    }

    @Override
    protected boolean inScope(PlatformModuleAction record, HttpServletRequest request) {
        return moduleAlias(request).equals(record.getModuleAlias());
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "module action does not belong to module: " + moduleAlias(request) + "." + id;
    }

    @DeleteMapping("/{id}/permission-governance")
    @ActionEndpoint(PlatformAction.UPDATE)
    public void clearPermissionGovernance(@PathVariable String moduleAlias,
                                          @PathVariable String id,
                                          @RequestBody RecordActionWebRequest request) {
        service().clearPermissionGovernanceOverrides(PlatformNameRules.requireModuleAlias(moduleAlias), id,
                request.version());
    }

    private String moduleAlias(HttpServletRequest request) {
        return PlatformNameRules.requireModuleAlias(pathVariable(request, "moduleAlias"));
    }
}
