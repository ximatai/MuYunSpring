package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.PageRequests;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class FieldUiControlBindingService extends AbstractAbilityService<FieldUiControlBinding> implements
        SoftDeleteAbility<FieldUiControlBinding>,
        SortAbility<FieldUiControlBinding>,
        ChildAbility<FieldUiControlBinding>,
        QueryAbility<FieldUiControlBinding> {
    public static final String MODULE_ALIAS = "platform.field_ui_control_binding";

    private final FieldUiControlService fieldUiControlService;
    private final FieldSpecService fieldSpecService;

    public FieldUiControlBindingService(BaseDao<FieldUiControlBinding, String> mappingDao,
                                        FieldUiControlService fieldUiControlService,
                                        FieldSpecService fieldSpecService) {
        super(MODULE_ALIAS, FieldUiControlBinding.class, mappingDao);
        this.fieldUiControlService = fieldUiControlService;
        this.fieldSpecService = fieldSpecService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, FieldUiControlBinding.class, java.util.List.of("id", "fieldUiControlAlias", "valueKey", "valueFieldSpecAlias", "title", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("valueKey"));
    }

    @Override
    public void beforeInsert(FieldUiControlBinding mapping) {
        normalizeAndValidate(mapping);
    }

    @Override
    public void beforeUpdate(FieldUiControlBinding mapping) {
        normalizeAndValidate(mapping);
        FieldUiControlBinding existing = selectIncludingDeleted(mapping.getId());
        rejectChanged(existing, mapping, "Field UI control binding value key", FieldUiControlBinding::getValueKey);
    }

    public List<FieldUiControlBinding> listByFieldUiControlAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        return list(Criteria.of().in("fieldUiControlAlias", aliases),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public FieldUiControlBinding findDeletedReplacement(FieldUiControlBinding incoming) {
        if (incoming == null || incoming.getFieldUiControlAlias() == null || incoming.getValueKey() == null) {
            return null;
        }
        return getDao().query(Criteria.of()
                        .eq("fieldUiControlAlias", incoming.getFieldUiControlAlias().trim())
                        .eq("valueKey", incoming.getValueKey().trim()), PageRequests.all())
                .stream()
                .filter(value -> Boolean.TRUE.equals(value.getDeleted()))
                .findFirst()
                .orElse(null);
    }

    private void normalizeAndValidate(FieldUiControlBinding mapping) {
        mapping.setFieldUiControlAlias(PlatformNameRules.requireIdentifier(
                mapping.getFieldUiControlAlias(), "fieldUiControlAlias"));
        mapping.setValueKey(PlatformNameRules.requireFieldName(mapping.getValueKey(), "valueKey"));
        mapping.setValueFieldSpecAlias(PlatformNameRules.requireIdentifier(
                mapping.getValueFieldSpecAlias(), "valueFieldSpecAlias"));
        FieldUiControl control = fieldUiControlService.requireFieldUiControl(mapping.getFieldUiControlAlias());
        if (control.getValueShape() != FieldUiControlValueShape.COMPOSITE) {
            throw new PlatformException("Field UI control bindings require COMPOSITE value shape: "
                    + control.getAlias());
        }
        fieldSpecService.requireFieldType(mapping.getValueFieldSpecAlias());
        if (mapping.getTitle() == null || mapping.getTitle().isBlank()) {
            mapping.setTitle(mapping.getValueKey());
        }
        rejectDuplicate(mapping, Criteria.of()
                        .eq("fieldUiControlAlias", mapping.getFieldUiControlAlias())
                        .eq("valueKey", mapping.getValueKey()),
                "field UI control mapping must be unique: " + mapping.getFieldUiControlAlias()
                        + "." + mapping.getValueKey());
    }
}
