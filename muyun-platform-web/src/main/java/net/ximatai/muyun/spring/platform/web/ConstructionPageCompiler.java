package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.application.ApplicationConstructionPageCompiler;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import org.springframework.stereotype.Component;

@Component
public class ConstructionPageCompiler implements ApplicationConstructionPageCompiler {
    private final PlatformModuleRuntimeContextService runtime;
    public ConstructionPageCompiler(PlatformModuleRuntimeContextService runtime) { this.runtime = runtime; }
    @Override public void validate(PlatformPageDefinition page, PlatformPresentationRevision revision) {
        runtime.previewDynamicPageDescriptor(page, revision, revision.getUiTreeJson());
    }
}
