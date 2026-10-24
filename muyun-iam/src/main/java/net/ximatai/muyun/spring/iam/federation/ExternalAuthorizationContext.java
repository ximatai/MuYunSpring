package net.ximatai.muyun.spring.iam.federation;

import java.net.URI;
import java.util.Objects;

/**
 * Per-transaction values a protocol needs to talk to its provider.
 *
 * @param state       opaque one-time value that the provider must echo back unchanged as {@code state}
 * @param callbackUri registered workbench address the provider returns the browser to; must match between
 *                    authorization and verification
 * @param apiBaseUri  public base address of the platform HTTP API, for protocols that serve their own pages
 */
public record ExternalAuthorizationContext(String state, URI callbackUri, URI apiBaseUri) {
    public ExternalAuthorizationContext {
        Objects.requireNonNull(callbackUri, "callbackUri");
        Objects.requireNonNull(apiBaseUri, "apiBaseUri");
    }
}
