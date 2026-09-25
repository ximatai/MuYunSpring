package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;

/** Platform-governed endpoint definition that tenant model connections may use. */
@Getter
@Setter
@Table(name = "platform_ai_model_provider", comment = "AI model provider")
@InitialDataFields(operator = {"title", "protocol", "baseUrl", "enabled", "sortOrder"})
@Required(fields = "title")
@NormalizeText(fields = "title")
public class AiModelProvider extends StandardEnabledSortableEntity {
    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "protocol", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Provider API protocol")
    private AiModelProtocol protocol = AiModelProtocol.OPENAI_COMPATIBLE;

    @Column(name = "base_url", type = ColumnType.VARCHAR, length = 512, nullable = false,
            comment = "Provider API base URL")
    @Required
    @NormalizeText
    private String baseUrl;
}
