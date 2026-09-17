package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.common.security.EncryptedField;
import net.ximatai.muyun.spring.common.security.SignedField;

/** One effective LLM connection in either the global or a tenant scope. */
@Getter
@Setter
@Table(name = "platform_ai_model_configuration", comment = "AI model connection configuration")
public class AiModelConfiguration extends StandardEnabledSortableEntity {
    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "provider", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "AI provider")
    private AiModelProvider provider;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "protocol", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Model API protocol")
    private AiModelProtocol protocol = AiModelProtocol.OPENAI_COMPATIBLE;

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

    /** Safe read projection for pages and APIs; never persisted. */
    private transient Boolean apiKeyConfigured;
}
