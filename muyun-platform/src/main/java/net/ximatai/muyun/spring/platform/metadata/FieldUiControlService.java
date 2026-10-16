package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class FieldUiControlService extends AbstractAbilityService<FieldUiControl> implements
        net.ximatai.muyun.spring.platform.localization.ConfiguredRecordTextAbility<FieldUiControl>,
        RecycleBinAbility<FieldUiControl>,
        EnableAbility<FieldUiControl>,
        SortAbility<FieldUiControl>,
        ChildrenAbility<FieldUiControl>,
        ReferenceAbility<FieldUiControl>,
        QueryAbility<FieldUiControl> {
    public static final String MODULE_ALIAS = "platform.field_ui_control";

    private final FieldSpecService fieldTypeService;
    private final BaseDao<PlatformUiConfigField, String> uiConfigFieldDao;

    public FieldUiControlService(BaseDao<FieldUiControl, String> fieldUiTypeDao,
                                 FieldSpecService fieldTypeService,
                                 BaseDao<PlatformUiConfigField, String> uiConfigFieldDao) {
        super(MODULE_ALIAS, FieldUiControl.class, fieldUiTypeDao);
        this.fieldTypeService = Objects.requireNonNull(fieldTypeService, "fieldTypeService");
        this.uiConfigFieldDao = Objects.requireNonNull(uiConfigFieldDao, "uiConfigFieldDao");
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, FieldUiControl.class, java.util.List.of("id", "alias", "title", "defaultFieldSpecAlias", "valueShape", "primaryValueKey", "queryMode", "rendererType", "icon", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforePrepareInsert(FieldUiControl fieldUiType) {
        fieldUiType.setId(PlatformNameRules.requireIdentifier(fieldUiType.getAlias(), "fieldUiControlAlias"));
    }

    @Override
    public void beforeInsert(FieldUiControl fieldUiType) {
        normalizeAndValidate(fieldUiType);
    }

    @Override
    public void beforeUpdate(FieldUiControl fieldUiType) {
        normalizeAndValidate(fieldUiType);
        FieldUiControl existing = selectIncludingDeleted(fieldUiType.getId());
        rejectChangedText(existing, fieldUiType, TextReference.code(
                "platform.rejection.field-ui-control.field-ui-type-alias-cannot-be-changed",
                "Field UI type alias cannot be changed"), FieldUiControl::getAlias);
        rejectDisableWhenReferenced(existing, fieldUiType);
    }

    public FieldUiControl requireFieldUiControl(String alias) {
        String validAlias = PlatformNameRules.requireIdentifier(alias, "fieldUiControlAlias");
        FieldUiControl fieldUiType = findOne(Criteria.of().eq("alias", validAlias));
        if (fieldUiType == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.field-ui-control.field-ui-type-requires-existing-type",
                    "Field UI type requires existing type: {value1}"), Map.of("value1", String.valueOf(validAlias)));
        }
        return fieldUiType;
    }

    public List<FieldUiControl> listEnabledByAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        return list(enabledCriteria(Criteria.of().in("alias", aliases)),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    public List<FieldUiControl> listEnabledForDefaultFieldType(String fieldSpecAlias) {
        String validAlias = PlatformNameRules.requireIdentifier(fieldSpecAlias, "fieldSpecAlias");
        return list(enabledCriteria(Criteria.of().eq("defaultFieldSpecAlias", validAlias)),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    private void normalizeAndValidate(FieldUiControl fieldUiType) {
        String alias = PlatformNameRules.requireIdentifier(fieldUiType.getAlias(), "fieldUiControlAlias");
        fieldUiType.setAlias(alias);
        if (fieldUiType.getTitle() == null || fieldUiType.getTitle().isBlank()) {
            fieldUiType.setTitle(alias);
        }
        if (fieldUiType.getValueShape() == null) {
            fieldUiType.setValueShape(FieldUiControlValueShape.SCALAR);
        }
        normalizePrimaryValueKey(fieldUiType);
        if (fieldUiType.getValueShape() != FieldUiControlValueShape.COMPOSITE) {
            // Bindings are composite components, not merely a conditionally visible UI section.
            // An explicit empty aggregate lets ChildrenAbility retire existing rows on every
            // standard write path, including callers that do not originate from the web form.
            fieldUiType.setBindings(List.of());
        }
        if (fieldUiType.getQueryMode() == null) fieldUiType.setQueryMode(FieldUiControlQueryMode.DEFAULT);
        if (fieldUiType.getQueryMode() == FieldUiControlQueryMode.BETWEEN
                && fieldUiType.getValueShape() != FieldUiControlValueShape.COMPOSITE) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.field-ui-control.between-query-mode-requires-composite-field-ui-control",
                    "BETWEEN query mode requires COMPOSITE field UI control: {value1}"), Map.of("value1", String.valueOf(alias)));
        }
        if (fieldUiType.getDefaultFieldSpecAlias() != null && !fieldUiType.getDefaultFieldSpecAlias().isBlank()) {
            fieldUiType.setDefaultFieldSpecAlias(PlatformNameRules.requireIdentifier(
                    fieldUiType.getDefaultFieldSpecAlias(), "defaultFieldSpecAlias"));
            fieldTypeService.requireFieldType(fieldUiType.getDefaultFieldSpecAlias());
        }
        rejectDuplicate(fieldUiType, Criteria.of().eq("alias", alias), () -> PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.field-ui-control.fielduicontrolalias-must-be-unique",
                "fieldUiControlAlias must be unique: {value1}"), Map.of("value1", String.valueOf(alias))));
    }

    private void normalizePrimaryValueKey(FieldUiControl fieldUiControl) {
        if (fieldUiControl.getValueShape() != FieldUiControlValueShape.COMPOSITE) {
            // Switching away from COMPOSITE makes the former primary component irrelevant.
            // Treat the hidden stale value as input to normalize instead of forcing clients to
            // understand and clear this internal invariant before a standard save.
            fieldUiControl.setPrimaryValueKey(null);
            return;
        }
        fieldUiControl.setPrimaryValueKey(PlatformNameRules.requireFieldName(
                fieldUiControl.getPrimaryValueKey(), "primaryValueKey"));
    }

    private void rejectDisableWhenReferenced(FieldUiControl existing, FieldUiControl updated) {
        if (existing == null || !Boolean.FALSE.equals(updated.getEnabled())) {
            return;
        }
        boolean referenced = !uiConfigFieldDao.list(Criteria.of().eq("fieldUiControlAlias", existing.getAlias()),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, 1)).isEmpty();
        if (referenced) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.field-ui-control.field-ui-control-is-referenced-by-ui-config-fields-and-cannot-be",
                    "Field UI control is referenced by UI config fields and cannot be disabled: {value1}"), Map.of("value1", String.valueOf(existing.getAlias())));
        }
    }

}
