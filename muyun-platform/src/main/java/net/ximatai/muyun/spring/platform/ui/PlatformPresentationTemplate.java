package net.ximatai.muyun.spring.platform.ui;

import java.util.Set;

/**
 * Versioned, executable contract of a composition root.  Templates are platform code rather
 * than tenant data: their slots are the boundary within which a presentation revision may vary.
 */
public record PlatformPresentationTemplate(
        String alias,
        int version,
        PlatformPresentationClientType clientType,
        Set<PlatformPageContractType> supportedPageContracts,
        String defaultUiTreeJson) {

    public PlatformPresentationTemplate {
        supportedPageContracts = Set.copyOf(supportedPageContracts);
    }
}
