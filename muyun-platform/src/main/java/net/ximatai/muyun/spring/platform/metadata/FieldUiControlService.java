package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class FieldUiControlService extends AbstractAbilityService<FieldUiControl> implements
        SoftDeleteAbility<FieldUiControl>,
        EnableAbility<FieldUiControl>,
        SortAbility<FieldUiControl>,
        ReferenceAbility<FieldUiControl>,
        QueryAbility<FieldUiControl> {
    public static final String MODULE_ALIAS = "platform.field_ui_control";

    private final FieldSpecService fieldTypeService;
    private final BaseDao<PlatformUiConfigField, String> uiConfigFieldDao;

    public FieldUiControlService(BaseDao<FieldUiControl, String> fieldUiTypeDao,
                                      FieldSpecService fieldTypeService) {
        this(fieldUiTypeDao, fieldTypeService, null);
    }

    @Autowired
    public FieldUiControlService(BaseDao<FieldUiControl, String> fieldUiTypeDao,
                                 FieldSpecService fieldTypeService,
                                 BaseDao<PlatformUiConfigField, String> uiConfigFieldDao) {
        super(MODULE_ALIAS, FieldUiControl.class, fieldUiTypeDao);
        this.fieldTypeService = fieldTypeService;
        this.uiConfigFieldDao = uiConfigFieldDao;
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
        rejectChanged(existing, fieldUiType, "Field UI type alias", FieldUiControl::getAlias);
        rejectDisableWhenReferenced(existing, fieldUiType);
    }

    public FieldUiControl requireFieldUiControl(String alias) {
        String validAlias = PlatformNameRules.requireIdentifier(alias, "fieldUiControlAlias");
        FieldUiControl fieldUiType = findOne(Criteria.of().eq("alias", validAlias));
        if (fieldUiType == null) {
            throw new PlatformException("Field UI type requires existing type: " + validAlias);
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
        if (fieldUiType.getQueryMode() == null) fieldUiType.setQueryMode(FieldUiControlQueryMode.DEFAULT);
        if (fieldUiType.getQueryMode() == FieldUiControlQueryMode.BETWEEN
                && fieldUiType.getValueShape() != FieldUiControlValueShape.COMPOSITE) {
            throw new PlatformException("BETWEEN query mode requires COMPOSITE field UI control: " + alias);
        }
        if (fieldUiType.getDefaultFieldSpecAlias() != null && !fieldUiType.getDefaultFieldSpecAlias().isBlank()) {
            fieldUiType.setDefaultFieldSpecAlias(PlatformNameRules.requireIdentifier(
                    fieldUiType.getDefaultFieldSpecAlias(), "defaultFieldSpecAlias"));
            fieldTypeService.requireFieldType(fieldUiType.getDefaultFieldSpecAlias());
        }
        rejectDuplicate(fieldUiType, Criteria.of().eq("alias", alias),
                "fieldUiControlAlias must be unique: " + alias);
    }

    private void normalizePrimaryValueKey(FieldUiControl fieldUiControl) {
        if (fieldUiControl.getValueShape() != FieldUiControlValueShape.COMPOSITE) {
            if (fieldUiControl.getPrimaryValueKey() != null && !fieldUiControl.getPrimaryValueKey().isBlank()) {
                throw new PlatformException("primaryValueKey is only allowed for COMPOSITE field UI controls: "
                        + fieldUiControl.getAlias());
            }
            fieldUiControl.setPrimaryValueKey(null);
            return;
        }
        fieldUiControl.setPrimaryValueKey(PlatformNameRules.requireFieldName(
                fieldUiControl.getPrimaryValueKey(), "primaryValueKey"));
    }

    private void rejectDisableWhenReferenced(FieldUiControl existing, FieldUiControl updated) {
        if (uiConfigFieldDao == null || existing == null || !Boolean.FALSE.equals(updated.getEnabled())) {
            return;
        }
        boolean referenced = !uiConfigFieldDao.list(Criteria.of().eq("fieldUiControlAlias", existing.getAlias()),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, 1)).isEmpty();
        if (referenced) {
            throw new PlatformException("Field UI control is referenced by UI config fields and cannot be disabled: "
                    + existing.getAlias());
        }
    }
}
