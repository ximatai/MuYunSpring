package net.ximatai.muyun.spring.demo.quality.equipment;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

@Service @Profile("collaboration-demo")
public class EquipmentCategoryService extends StandardBusinessService<EquipmentCategory> implements
        SoftDeleteAbility<EquipmentCategory>, EnableAbility<EquipmentCategory>, TreeAbility<EquipmentCategory>,
        ReferenceAbility<EquipmentCategory> {
    public static final String MODULE_ALIAS = "quality.equipment_category";
    public EquipmentCategoryService(EquipmentCategoryDao dao) { super(MODULE_ALIAS, EquipmentCategory.class, dao); }
}
