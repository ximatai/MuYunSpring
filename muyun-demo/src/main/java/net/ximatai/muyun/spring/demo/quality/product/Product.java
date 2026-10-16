package net.ximatai.muyun.spring.demo.quality.product;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.reference.*;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.model.constraint.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledEntity;
import net.ximatai.muyun.spring.demo.quality.equipment.EquipmentCategoryService;

/** The exception links a product ID; a readable name never becomes its foreign key. */
@Getter @Setter
@Table(name = "quality_product", comment = "商品目录")
@BusinessContent(field = "title", key = "product-name", value = ContentPolicy.MANUAL_TRANSLATIONS)
@Required(fields = {"title", "code", "categoryId"})
@TenantUniqueConstraint(fields = "code", message = "商品编码在租户内不能重复")
public class Product extends StandardEnabledEntity {
    @Column(name = "code", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "商品编码")
    private String code;
    @ReferenceTo(target = EquipmentCategoryService.class)
    @Column(name = "category_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "设备分类")
    private String categoryId;
    @ReferenceLoad(source = "categoryId", field = "title")
    private transient String categoryTitle;
    @BusinessContent(key = "product-description")
    @Column(name = "description", type = ColumnType.TEXT, comment = "商品说明")
    private String description;
}
