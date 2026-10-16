package net.ximatai.muyun.spring.demo.quality.equipment;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.query.*;
import net.ximatai.muyun.database.core.orm.Sort;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

@Service @Profile("collaboration-demo")
public class EquipmentCategoryService extends StandardBusinessService<EquipmentCategory> implements
        SoftDeleteAbility<EquipmentCategory>, EnableAbility<EquipmentCategory>, TreeAbility<EquipmentCategory>,
        ReferenceAbility<EquipmentCategory>, QueryAbility<EquipmentCategory> {
    public static final String MODULE_ALIAS = "quality.equipment_category";
    public EquipmentCategoryService(EquipmentCategoryDao dao) { super(MODULE_ALIAS, EquipmentCategory.class, dao); }

    @Override public QueryDescriptor queryDescriptor() {
        return QueryDescriptor.builder(MODULE_ALIAS)
                .field(QueryDescriptors.field(EquipmentCategory.class, "id").withSortable())
                .field(QueryDescriptors.field(EquipmentCategory.class, "code").withQuickSearch().withSortable())
                .field(QueryDescriptors.field(EquipmentCategory.class, "title").withQuickSearch().withSortable())
                .field(QueryDescriptors.field(EquipmentCategory.class, "parentId"))
                .field(QueryDescriptors.field(EquipmentCategory.class, "enabled"))
                .defaultSort(Sort.asc("code")).defaultSort(Sort.asc("id"))
                .build();
    }
}
