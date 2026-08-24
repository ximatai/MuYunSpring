package net.ximatai.muyun.spring.platform.dictionary;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledTreeEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.platform.application.ApplicationService;

@Getter
@Setter
@Table(name = "platform_dictionary_category", comment = "Platform dictionary category")
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "applicationAlias")
@TenantUniqueConstraint(fields = {"applicationAlias", "alias"})
public class DictionaryCategory extends StandardEnabledTreeEntity {
    @Column(name = "application_alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Application alias")
    @ReferenceTo(target = ApplicationService.class)
    private String applicationAlias;

    @Column(name = "alias", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Dictionary category alias")
    private String alias;

    @Column(name = "category_kind", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Dictionary category kind", defaultVal = @Default(varchar = "dictionary"))
    @OptionField(type = OptionSourceType.ENUM)
    private DictionaryCategoryKind categoryKind = DictionaryCategoryKind.DICTIONARY;
}
