package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledEntity;
import net.ximatai.muyun.spring.common.security.EncryptedField;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.common.security.SignedField;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;

/** One LLM connection owned by either the platform or the current tenant. */
@Getter
@Setter
@Table(name = "platform_ai_model_configuration", comment = "AI model connection configuration")
@CompositeIndex(columns = {"ownership_scope_key"}, unique = true)
public class AiModelConfiguration extends StandardEnabledEntity {
    @Column(name = "provider", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "AI provider")
    @ReferenceTo(target = AiModelProviderService.class, tenantScope = ReferenceTenantScope.GLOBAL)
    private String provider;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "configuration_level", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Configuration ownership level")
    private AiModelConfigurationLevel configurationLevel = AiModelConfigurationLevel.PLATFORM;

    @Column(name = "tenant_fallback_enabled", type = ColumnType.BOOLEAN, nullable = false,
            comment = "Whether tenants without a private configuration may use this platform configuration",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean tenantFallbackEnabled = Boolean.FALSE;

    @Column(name = "model_id", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Provider model id")
    private String modelId;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "credential_source", type = ColumnType.VARCHAR, length = 32, nullable = false,
            defaultVal = @Default(varchar = "direct"), comment = "API key source")
    private AiModelCredentialSource credentialSource = AiModelCredentialSource.DIRECT;

    @Column(name = "api_key_environment_variable", type = ColumnType.VARCHAR, length = 128,
            comment = "Server environment variable containing the API key")
    private String apiKeyEnvironmentVariable;

    @JsonIgnore
    @EncryptedField
    @SignedField
    @Column(name = "api_key", type = ColumnType.TEXT, comment = "Encrypted provider API key")
    private String apiKey;

    @JsonIgnore
    @Column(name = "api_key_signature", type = ColumnType.VARCHAR, length = 128, comment = "API key integrity signature")
    private String apiKeySignature;

    /** Accepted only on writes. A blank value retains the configured key during updates. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private transient String apiKeyInput;

    /** Safe, non-sensitive indication that a credential has been configured. */
    @Column(name = "api_key_configured", type = ColumnType.BOOLEAN, nullable = false,
            comment = "Whether API key is configured", defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean apiKeyConfigured = Boolean.FALSE;

    /** Internal unique key for the one-global-or-one-per-tenant invariant. */
    @JsonIgnore
    @Column(name = "ownership_scope_key", type = ColumnType.VARCHAR, length = 64,
            comment = "Unique AI configuration ownership key")
    private String ownershipScopeKey;
}
