package net.ximatai.muyun.spring.demo.quality.equipment;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.model.constraint.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledTreeEntity;

/** Stable master identity; equal names in different business contexts remain distinct records. */
@Getter @Setter
@Table(name = "quality_equipment_category", comment = "设备分类")
@BusinessContent(field = "title", key = "equipment-category-name", value = ContentPolicy.MANUAL_TRANSLATIONS)
@Required(fields = {"title", "code"})
@TenantUniqueConstraint(fields = "code", message = "设备分类编码在租户内不能重复")
public class EquipmentCategory extends StandardEnabledTreeEntity {
    @Column(name = "code", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "分类编码")
    private String code;
    /** Deliberately not declared as multilingual business content. */
    @Column(name = "remark", type = ColumnType.TEXT, comment = "备注")
    private String remark;
}
