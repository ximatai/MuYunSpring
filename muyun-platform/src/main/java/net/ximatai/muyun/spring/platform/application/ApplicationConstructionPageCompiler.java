package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;

/** Delivery adapter compiles a candidate with the same rules as the formal page runtime. */
public interface ApplicationConstructionPageCompiler {
    void validate(PlatformPageDefinition page, PlatformPresentationRevision revision);
}
