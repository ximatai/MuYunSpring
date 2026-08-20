package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class FieldUiControlPropertyService extends AbstractAbilityService<FieldUiControlProperty> implements
        SoftDeleteAbility<FieldUiControlProperty>,
        SortAbility<FieldUiControlProperty>,
        QueryAbility<FieldUiControlProperty> {
    public static final String MODULE_ALIAS = "platform.field_ui_control_property";

    private final FieldUiControlService fieldUiTypeService;
    private final FieldSpecService fieldTypeService;

    public FieldUiControlPropertyService(BaseDao<FieldUiControlProperty, String> attributeDao,
                                               FieldUiControlService fieldUiTypeService,
                                               FieldSpecService fieldTypeService) {
        super(MODULE_ALIAS, FieldUiControlProperty.class, attributeDao);
        this.fieldUiTypeService = fieldUiTypeService;
        this.fieldTypeService = fieldTypeService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, FieldUiControlProperty.class, java.util.List.of("id", "fieldUiControlAlias", "attributeAlias", "title", "valueFieldSpecAlias", "defaultValue", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("attributeAlias"));
    }

    @Override
    public void beforeInsert(FieldUiControlProperty attribute) {
        normalizeAndValidate(attribute);
    }

    @Override
    public void beforeUpdate(FieldUiControlProperty attribute) {
        normalizeAndValidate(attribute);
        FieldUiControlProperty existing = selectIncludingDeleted(attribute.getId());
        rejectChanged(existing, attribute, "Field UI control attribute alias",
                FieldUiControlProperty::getAttributeAlias);
    }

    public List<FieldUiControlProperty> listByFieldUiControlAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        return list(Criteria.of().in("fieldUiControlAlias", aliases),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    private void normalizeAndValidate(FieldUiControlProperty attribute) {
        attribute.setFieldUiControlAlias(PlatformNameRules.requireIdentifier(
                attribute.getFieldUiControlAlias(), "fieldUiControlAlias"));
        attribute.setAttributeAlias(PlatformNameRules.requireFieldName(
                attribute.getAttributeAlias(), "attributeAlias"));
        fieldUiTypeService.requireFieldUiControl(attribute.getFieldUiControlAlias());
        if (attribute.getTitle() == null || attribute.getTitle().isBlank()) {
            attribute.setTitle(attribute.getAttributeAlias());
        }
        if (attribute.getValueFieldSpecAlias() != null && !attribute.getValueFieldSpecAlias().isBlank()) {
            attribute.setValueFieldSpecAlias(PlatformNameRules.requireIdentifier(
                    attribute.getValueFieldSpecAlias(), "valueFieldSpecAlias"));
            fieldTypeService.requireFieldType(attribute.getValueFieldSpecAlias());
        }
        rejectDuplicate(attribute, Criteria.of()
                        .eq("fieldUiControlAlias", attribute.getFieldUiControlAlias())
                        .eq("attributeAlias", attribute.getAttributeAlias()),
                "field UI control attribute must be unique: " + attribute.getFieldUiControlAlias()
                        + "." + attribute.getAttributeAlias());
    }
}
