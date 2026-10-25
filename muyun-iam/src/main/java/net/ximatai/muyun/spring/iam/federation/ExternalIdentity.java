package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.iam.user.UserAccountService;

import java.time.Instant;

/**
 * Links a verified external identity to one local account in the account's tenant. Within a tenant an
 * external identity maps to at most one account and an account binds each provider at most once; the same
 * person may hold separate bindings in other tenants. Unbinding removes the row physically, so the
 * uniqueness indexes always reflect the live bindings.
 */
@Getter
@Setter
@Table(name = "iam_external_identity", comment = "External identity binding")
@TenantUniqueConstraint(fields = {"identityNamespace", "externalEnterpriseId", "externalUserId"},
        message = "external identity is already bound to another account")
@TenantUniqueConstraint(fields = {"userId", "providerId"},
        message = "account already has a binding for this identity provider")
public class ExternalIdentity extends StandardEntity {
    @Column(name = "user_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Local account id")
    @ReferenceTo(target = UserAccountService.class)
    @Required
    private String userId;

    @ReferenceLoad(source = "userId", field = "title")
    private transient String userTitle;

    @Column(name = "provider_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Identity provider id")
    @ReferenceTo(target = IdentityProviderService.class, tenantScope = ReferenceTenantScope.GLOBAL)
    @Required
    private String providerId;

    @ReferenceLoad(source = "providerId", field = "title")
    private transient String providerTitle;

    @Column(name = "identity_namespace", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Uniqueness scope of the external user id")
    @Required
    private String identityNamespace;

    @Column(name = "external_enterprise_id", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "External enterprise id")
    @Required
    private String externalEnterpriseId;

    @Column(name = "external_user_id", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "External user id")
    @Required
    private String externalUserId;

    @Column(name = "display_name", type = ColumnType.VARCHAR, length = 128,
            comment = "External display name snapshot")
    private String displayName;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "binding_source", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "How the binding was created")
    private ExternalIdentityBindingSource bindingSource;

    @OptionLoad(source = "bindingSource")
    private transient String bindingSourceTitle;

    @Column(name = "bound_at", type = ColumnType.TIMESTAMP, nullable = false, comment = "Bound at")
    private Instant boundAt;

    @Column(name = "last_authenticated_at", type = ColumnType.TIMESTAMP,
            comment = "Last successful sign-in through this identity")
    private Instant lastAuthenticatedAt;
}
