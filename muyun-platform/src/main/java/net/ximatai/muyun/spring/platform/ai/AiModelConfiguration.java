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
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.security.EncryptedField;
import net.ximatai.muyun.spring.common.security.SignedField;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;


/** One ranked LLM connection owned by either the platform or the current tenant. */
@Getter
@Setter
@Table(name = "platform_ai_model_configuration", comment = "AI model connection configuration")
@CompositeIndex(columns = {"ownership_scope_key"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = {"tenantId", "availabilityScope"})
public class AiModelConfiguration extends StandardEnabledSortableEntity {
    @Column(name = "provider", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "AI provider")
    @ReferenceTo(target = AiModelProviderService.class, tenantScope = ReferenceTenantScope.GLOBAL)
    private String provider;

    @net.ximatai.muyun.spring.common.option.OptionField(type = net.ximatai.muyun.spring.common.option.OptionSourceType.ENUM)
    @Column(name = "availability_scope", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Tenant availability scope")
    private AiModelAvailabilityScope availabilityScope = AiModelAvailabilityScope.PLATFORM;

    @Column(name = "model_id", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Provider model id")
    private String modelId;

    @JsonIgnore
    @EncryptedField
    @SignedField
    @Column(name = "api_key", type = ColumnType.TEXT, nullable = false, comment = "Encrypted provider API key")
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
