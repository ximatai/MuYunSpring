package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.PageRequests;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class FieldUiControlBindingService extends AbstractAbilityService<FieldUiControlBinding> implements
        net.ximatai.muyun.spring.platform.localization.ConfiguredRecordTextAbility<FieldUiControlBinding>,
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
        rejectChangedText(existing, mapping, TextReference.code(
                "platform.rejection.field-ui-control-binding.field-ui-control-binding-value-key-cannot-be-changed",
                "Field UI control binding value key cannot be changed"), FieldUiControlBinding::getValueKey);
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.field-ui-control-binding.field-ui-control-bindings-require-composite-value-shape",
                    "Field UI control bindings require COMPOSITE value shape: {value1}"), Map.of("value1", String.valueOf(control.getAlias())));
        }
        fieldSpecService.requireFieldType(mapping.getValueFieldSpecAlias());
        if (mapping.getTitle() == null || mapping.getTitle().isBlank()) {
            mapping.setTitle(mapping.getValueKey());
        }
        rejectDuplicate(mapping, Criteria.of()
                        .eq("fieldUiControlAlias", mapping.getFieldUiControlAlias())
                        .eq("valueKey", mapping.getValueKey()), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.field-ui-control-binding.field-ui-control-mapping-must-be-unique",
                                "field UI control mapping must be unique: {value1}.{value2}"), Map.of("value1", String.valueOf(mapping.getFieldUiControlAlias()), "value2", String.valueOf(mapping.getValueKey()))));
    }

}
