package net.ximatai.muyun.spring.demo.quality.product;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

@Service @Profile("collaboration-demo")
public class ProductService extends StandardBusinessService<Product> implements
        SoftDeleteAbility<Product>, EnableAbility<Product>, ReferenceAbility<Product> {
    public static final String MODULE_ALIAS = "quality.product";
    public ProductService(ProductDao dao) { super(MODULE_ALIAS, Product.class, dao); }
}
