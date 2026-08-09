package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.PlatformPageEntryContext;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;

/**
 * Source-neutral page entry projection for the standard module runner.
 */
public record PlatformPageBootstrapResponse(
        PlatformPageEntryContext entry,
        PlatformUiClientType clientType,
        String mainEntityAlias,
        PlatformResolvedPageConfig resolvedConfig,
        String openApiPath
) {
}
