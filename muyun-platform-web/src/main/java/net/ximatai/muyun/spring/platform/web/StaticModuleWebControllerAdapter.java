package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Source-neutral execution bridge for a static standard-module controller.
 *
 * <p>A business controller only declares its module contract and UI contribution.  Wiring the
 * compiled-plan runtime is platform infrastructure, so it belongs here rather than being
 * repeated as an optional dependency and a strict-mode flag on every controller.</p>
 */
public abstract class StaticModuleWebControllerAdapter<S> extends WebSupport<S> {
    private StandardModuleWebRuntime standardModuleWebRuntime;
    private ManagedDetailRelationGateway managedDetailRelationGateway;

    @Autowired(required = false)
    public final void setStandardModuleWebRuntime(StandardModuleWebRuntime standardModuleWebRuntime) {
        this.standardModuleWebRuntime = standardModuleWebRuntime;
    }

    /**
     * Internal {@link CrudWeb} bridge.  Standard static controllers never fall back to request
     * time declaration compilation when the bridge or a compiled plan is absent.
     */
    public final StandardModuleWebRuntime standardModuleWebRuntime() {
        return standardModuleWebRuntime;
    }

    public final boolean requiresModuleExecutionPlan() {
        return true;
    }

    @Autowired(required = false)
    public final void setManagedDetailRelationGateway(ManagedDetailRelationGateway managedDetailRelationGateway) {
        this.managedDetailRelationGateway = managedDetailRelationGateway;
    }

    public final ManagedDetailRelationGateway managedDetailRelationGateway() {
        return managedDetailRelationGateway;
    }
}
