package net.ximatai.muyun.spring.iam.federation;

import java.util.Map;
import java.util.Objects;

/**
 * Read-only view of an identity provider handed to its protocol. The platform resolves the client secret
 * from the configured server environment variable; the secret never appears in {@link #toString()}.
 */
public final class IdentityProviderDescriptor {
    private final String id;
    private final String providerType;
    private final String externalEnterpriseId;
    private final String identityNamespace;
    private final String clientId;
    private final String clientSecret;
    private final Map<String, String> configuration;

    public IdentityProviderDescriptor(String id, String providerType, String externalEnterpriseId,
                                      String identityNamespace, String clientId, String clientSecret,
                                      Map<String, String> configuration) {
        this.id = Objects.requireNonNull(id, "id");
        this.providerType = Objects.requireNonNull(providerType, "providerType");
        this.externalEnterpriseId = externalEnterpriseId;
        this.identityNamespace = Objects.requireNonNull(identityNamespace, "identityNamespace");
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }

    /** Stable provider code. */
    public String id() {
        return id;
    }

    public String providerType() {
        return providerType;
    }

    /** Enterprise the platform accepts identities from; {@code null} when not restricted. */
    public String externalEnterpriseId() {
        return externalEnterpriseId;
    }

    public String identityNamespace() {
        return identityNamespace;
    }

    public String clientId() {
        return clientId;
    }

    /** Resolved client secret, or {@code null} when none is configured or the variable is unset. */
    public String clientSecret() {
        return clientSecret;
    }

    /** Protocol-specific settings such as endpoints or scopes, validated by the protocol on save. */
    public Map<String, String> configuration() {
        return configuration;
    }

    /** The verified authority must still be the same when final admission obtains its lock. */
    boolean sameAuthority(IdentityProviderDescriptor other) {
        return other != null && id.equals(other.id) && providerType.equals(other.providerType)
                && Objects.equals(externalEnterpriseId, other.externalEnterpriseId)
                && identityNamespace.equals(other.identityNamespace) && Objects.equals(clientId, other.clientId)
                && Objects.equals(clientSecret, other.clientSecret) && configuration.equals(other.configuration);
    }

    /** Nonreversible authority stamp for directory facts; credential values are never persisted. */
    String authorityFingerprint() {
        try {
            var values = new java.util.ArrayList<Object>();
            values.add(id); values.add(providerType); values.add(externalEnterpriseId); values.add(identityNamespace);
            values.add(clientId); values.add(clientSecret); values.add(new java.util.TreeMap<>(configuration));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(DirectorySyncStore.encode(values).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override
    public String toString() {
        return "IdentityProviderDescriptor[id=" + id + ", providerType=" + providerType
                + ", externalEnterpriseId=" + externalEnterpriseId + ", identityNamespace=" + identityNamespace
                + ", clientId=" + clientId + ", configuration=" + configuration + "]";
    }
}
