package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;

/**
 * Platform-level external identity source maintained by platform super administrators. Its record id is
 * the stable provider code referenced by tenant policies, bindings and the login method recorded on sessions.
 * Secrets are never stored; the record only names the server environment variable holding the client secret.
 */
@Getter
@Setter
@Table(name = "iam_identity_provider", comment = "External identity provider")
@Required(fields = "title")
@NormalizeText(fields = "title")
public class IdentityProvider extends StandardEnabledSortableEntity {
    /** Protocol implementation key, matched against an installed {@link ExternalLoginProtocol}. */
    @Column(name = "provider_type", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Login protocol type")
    @Required
    @NormalizeText
    private String providerType;

    @Column(name = "external_enterprise_id", type = ColumnType.VARCHAR, length = 128,
            comment = "Verified external enterprise id; empty skips the enterprise check")
    @NormalizeText(TextNormalization.TRIM_TO_NULL)
    private String externalEnterpriseId;

    /** True uniqueness scope of external user ids; defaults to the provider code when empty. */
    @Column(name = "identity_namespace", type = ColumnType.VARCHAR, length = 128,
            comment = "Uniqueness scope of external user ids")
    @NormalizeText(TextNormalization.TRIM_TO_NULL)
    private String identityNamespace;

    @Column(name = "client_id", type = ColumnType.VARCHAR, length = 256, comment = "Application client id")
    @NormalizeText(TextNormalization.TRIM_TO_NULL)
    private String clientId;

    @Column(name = "client_secret_environment_variable", type = ColumnType.VARCHAR, length = 128,
            comment = "Server environment variable holding the client secret")
    @NormalizeText(TextNormalization.TRIM_TO_NULL)
    private String clientSecretEnvironmentVariable;

    public String effectiveIdentityNamespace() {
        return identityNamespace == null || identityNamespace.isBlank() ? getId() : identityNamespace;
    }
}
