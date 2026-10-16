package net.ximatai.muyun.spring.demo.quality.product;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.query.*;
import net.ximatai.muyun.database.core.orm.Sort;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

@Service @Profile("collaboration-demo")
public class ProductService extends StandardBusinessService<Product> implements
        SoftDeleteAbility<Product>, EnableAbility<Product>, ReferenceAbility<Product>, QueryAbility<Product> {
    public static final String MODULE_ALIAS = "quality.product";
    public ProductService(ProductDao dao) { super(MODULE_ALIAS, Product.class, dao); }

    @Override public QueryDescriptor queryDescriptor() {
        return QueryDescriptor.builder(MODULE_ALIAS)
                .field(QueryDescriptors.field(Product.class, "id").withSortable())
                .field(QueryDescriptors.field(Product.class, "code").withQuickSearch().withSortable())
                .field(QueryDescriptors.field(Product.class, "title").withQuickSearch().withSortable())
                .field(QueryDescriptors.field(Product.class, "categoryId"))
                .field(QueryDescriptors.field(Product.class, "enabled"))
                .field(QueryField.of("description", QueryValueType.TEXT, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle(net.ximatai.muyun.spring.common.i18n.TextReference.code(
                                "quality.product/query/description/title", "商品说明")).withQuickSearch())
                .defaultSort(Sort.asc("code")).defaultSort(Sort.asc("id"))
                .build();
    }
}
