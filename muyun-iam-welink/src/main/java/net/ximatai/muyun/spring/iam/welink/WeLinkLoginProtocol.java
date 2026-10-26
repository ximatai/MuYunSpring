package net.ximatai.muyun.spring.iam.welink;

import net.ximatai.muyun.spring.iam.federation.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** WeLink internal single-enterprise web QR sign-in. */
public final class WeLinkLoginProtocol implements ExternalLoginProtocol {
    private final WeLinkClient client;
    public WeLinkLoginProtocol(WeLinkClient client) { this.client = client; }
    public String providerType() { return "welink"; }
    public void validateConfiguration(Map<String, String> configuration) { client.configuration(configuration); }
    public Set<String> identityScopeConfigurationKeys() { return WeLinkConfiguration.AUTHORITY_KEYS; }
    public boolean singleEnterpriseApplication(IdentityProviderDescriptor provider) { return true; }
    public URI authorizationUri(IdentityProviderDescriptor provider, ExternalAuthorizationContext context) {
        var config = client.configuration(provider.configuration());
        WeLinkClient.credentials(provider);
        return URI.create(config.loginBase() + WeLinkConfiguration.AUTHORIZE_PATH + "?" + WeLinkClient.form(Map.of(
                "client_id", provider.clientId(), "redirect_uri", context.callbackUri().toString(),
                "state", context.state(), "scope", config.scope(), "response_type", "code")));
    }
    public QrCode qrCode(IdentityProviderDescriptor provider, ExternalAuthorizationContext context) {
        var config = client.configuration(provider.configuration());
        return new QrCode(providerType(), config.loginBase() + WeLinkConfiguration.SCRIPT_PATH,
                authorizationUri(provider, context).toString(), provider.clientId(), context.callbackUri().toString(),
                context.state(), config.scope(), config.loginBase());
    }
    public VerifiedExternalIdentity verify(IdentityProviderDescriptor provider, Map<String, String> parameters,
                                           ExternalAuthorizationContext context) {
        WeLinkClient.credentials(provider);
        String code = parameters.get("code");
        if (code == null || code.isBlank() || !Objects.equals(context.state(), parameters.get("state"))) throw WeLinkClient.rejected();
        var response = client.exchange(provider, Map.of("client_id", provider.clientId(), "client_secret", provider.clientSecret(),
                "grant_type", "authorization_code", "code", code, "redirect_uri", context.callbackUri().toString()));
        // The vendor error message is inspected, but never echoed: it can contain sensitive request data.
        if (!"success".equals(response.path("message").asText())) throw WeLinkClient.rejected();
        var identity = client.identity(provider, WeLinkClient.required(response, "access_token"));
        WeLinkClient.success(identity);
        return new VerifiedExternalIdentity(null, WeLinkClient.required(identity, "userId"), null, Instant.now());
    }
}
