package net.ximatai.muyun.spring.iam.federation;

import java.net.URI;
import java.time.Duration;

/**
 * Deployment addresses and limits of external identity federation, configured through
 * {@code muyun.iam.federation.*}.
 */
public interface ExternalIdentityFederationSettings {
    Duration DEFAULT_TRANSACTION_TTL = Duration.ofMinutes(3);

    /** Public base address of the platform HTTP API, used to build provider callback addresses. */
    URI apiBaseUrl();

    /** Workbench address the browser returns to after the provider callback. */
    URI workbenchUrl();

    Duration transactionTtl();
}
