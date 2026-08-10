package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class PlatformUiConfigFieldService extends AbstractAbilityService<PlatformUiConfigField> implements
        SoftDeleteAbility<PlatformUiConfigField>,
        EnableAbility<PlatformUiConfigField>,
        SortAbility<PlatformUiConfigField>,
        QueryAbility<PlatformUiConfigField> {
    public static final String MODULE_ALIAS = "platform.ui_config_field";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final PlatformUiConfigService uiConfigService;
    private final PlatformUiSetService uiSetService;
    private final ModuleMetadataFieldService moduleFieldService;
    private final FieldSpecService fieldTypeService;
    private final FieldUiControlService fieldUiTypeService;
    private final MetadataFieldService metadataFieldService;

    public PlatformUiConfigFieldService(BaseDao<PlatformUiConfigField, String> uiConfigFieldDao,
                                        PlatformUiConfigService uiConfigService,
                                        PlatformUiSetService uiSetService,
                                        ModuleMetadataFieldService moduleFieldService,
                                        FieldSpecService fieldTypeService,
                                        FieldUiControlService fieldUiTypeService,
                                        MetadataFieldService metadataFieldService) {
        super(MODULE_ALIAS, PlatformUiConfigField.class, uiConfigFieldDao);
        this.uiConfigService = uiConfigService;
        this.uiSetService = uiSetService;
        this.moduleFieldService = moduleFieldService;
        this.fieldTypeService = fieldTypeService;
        this.fieldUiTypeService = fieldUiTypeService;
        this.metadataFieldService = metadataFieldService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformUiConfigField.class, java.util.List.of("title", "moduleMetadataFieldId", "fieldUiControlAlias", "visible", "readOnly", "requiredOverride", "maxDisplayLines", "columnSpan", "enabled"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("title"));
    }

    @Override
    public void beforeInsert(PlatformUiConfigField field) {
        requireDraftUiConfig(field.getUiConfigId());
        normalizeAndValidate(field);
    }

    @Override
    public void beforeUpdate(PlatformUiConfigField field) {
        PlatformUiConfigField existing = selectIncludingDeleted(field.getId());
        requireDraftUiConfig(existing == null ? field.getUiConfigId() : existing.getUiConfigId());
        normalizeAndValidate(field);
        rejectChanged(existing, field, "UI config field config", PlatformUiConfigField::getUiConfigId);
        rejectChanged(existing, field, "UI config field module field",
                PlatformUiConfigField::getModuleMetadataFieldId);
    }

    @Override
    public void beforeDelete(String id) {
        PlatformUiConfigField existing = select(id);
        if (existing != null) {
            requireDraftUiConfig(existing.getUiConfigId());
        }
    }

    public List<PlatformUiConfigField> listByUiConfigIds(List<String> uiConfigIds) {
        if (uiConfigIds == null || uiConfigIds.isEmpty()) {
            return List.of();
        }
        return list(enabledCriteria(Criteria.of().in("uiConfigId", uiConfigIds)),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public void validateUiConfigFields(String uiConfigId) {
        PlatformUiConfig uiConfig = uiConfigService.requireUiConfig(uiConfigId);
        for (PlatformUiConfigField field : listByUiConfigIds(List.of(uiConfig.getId()))) {
            normalizeAndValidate(field);
        }
    }

    private void normalizeAndValidate(PlatformUiConfigField field) {
        PlatformUiConfig uiConfig = uiConfigService.requireUiConfig(field.getUiConfigId());
        PlatformUiSet uiSet = uiSetService.requireUiSet(uiConfig.getUiSetId());
        ResolvedModuleMetadataField moduleField = moduleFieldService.resolve(field.getModuleMetadataFieldId());
        if (!Objects.equals(uiSet.getModuleAlias(), moduleField.moduleAlias())) {
            throw new PlatformException("UI config field requires module field in the same module: "
                    + uiSet.getModuleAlias() + "." + moduleField.moduleAlias());
        }
        normalizeUiType(field, moduleField);
        validateRequiredOverride(field, moduleField);
        if (field.getVisible() == null) {
            field.setVisible(Boolean.TRUE);
        }
        if (field.getReadOnly() == null) {
            field.setReadOnly(Boolean.FALSE);
        }
        if (field.getColumnSpan() == null) {
            field.setColumnSpan(1);
        }
        if (field.getColumnSpan() < 1 || field.getColumnSpan() > 2) {
            throw new PlatformException("UI config field columnSpan must be between 1 and 2");
        }
        if (field.getMaxDisplayLines() != null && field.getMaxDisplayLines() < 1) {
            throw new PlatformException("UI config field maxDisplayLines must be at least 1");
        }
        if (field.getMaxDisplayLines() != null && uiSet.getSetType() != PlatformUiSetType.LIST) {
            throw new PlatformException("UI config field maxDisplayLines is only supported by LIST UI sets");
        }
        if (field.getTitle() == null || field.getTitle().isBlank()) {
            field.setTitle(moduleField.fieldTitle());
        }
        rejectDuplicate(field, Criteria.of()
                        .eq("uiConfigId", uiConfig.getId())
                        .eq("moduleMetadataFieldId", moduleField.moduleMetadataFieldId()),
                "UI config field must be unique in UI config: "
                        + uiConfig.getId() + "." + moduleField.moduleMetadataFieldId());
        field.setUiConfigId(uiConfig.getId());
        field.setModuleMetadataFieldId(moduleField.moduleMetadataFieldId());
    }

    private void normalizeUiType(PlatformUiConfigField field, ResolvedModuleMetadataField moduleField) {
        FieldSpec fieldType = fieldTypeService.requireFieldType(moduleField.fieldSpecAlias());
        String uiTypeAlias = field.getFieldUiControlAlias();
        if (uiTypeAlias == null || uiTypeAlias.isBlank()) {
            uiTypeAlias = fieldType.getDefaultUiControlAlias();
        }
        if (uiTypeAlias == null || uiTypeAlias.isBlank()) {
            throw new PlatformException("Field UI type is required and field type has no default UI type: "
                    + moduleField.fieldSpecAlias());
        }
        uiTypeAlias = PlatformNameRules.requireIdentifier(uiTypeAlias, "fieldUiControlAlias");
        FieldUiControl uiType = fieldUiTypeService.requireFieldUiControl(uiTypeAlias);
        if (!Boolean.TRUE.equals(uiType.getEnabled())) {
            throw new PlatformException("Field UI control must be enabled: " + uiTypeAlias);
        }
        if (fieldType.getUiControlAliases() != null && !fieldType.getUiControlAliases().isEmpty()) {
            if (!fieldType.getUiControlAliases().contains(uiTypeAlias)) {
                throw new PlatformException("Field UI type is not allowed by field type: "
                        + moduleField.fieldSpecAlias() + "." + uiTypeAlias);
            }
        } else if (uiType.getDefaultFieldSpecAlias() != null
                && !uiType.getDefaultFieldSpecAlias().isBlank()
                && !Objects.equals(uiType.getDefaultFieldSpecAlias(), moduleField.fieldSpecAlias())) {
            throw new PlatformException("Field UI type default field type mismatch: "
                    + uiTypeAlias + "." + moduleField.fieldSpecAlias());
        }
        field.setFieldUiControlAlias(uiTypeAlias);
    }

    private void validateRequiredOverride(PlatformUiConfigField field, ResolvedModuleMetadataField moduleField) {
        if (!Boolean.FALSE.equals(field.getRequiredOverride())) {
            return;
        }
        MetadataField metadataField = metadataFieldService.select(moduleField.metadataFieldId());
        if (metadataField != null && Boolean.TRUE.equals(metadataField.getRequired())) {
            throw new PlatformException("UI config field cannot weaken required metadata field: "
                    + moduleField.fieldName());
        }
    }

    private void requireDraftUiConfig(String uiConfigId) {
        PlatformUiConfig uiConfig = uiConfigService.requireUiConfig(uiConfigId);
        if (Boolean.TRUE.equals(uiConfig.getPublished())) {
            throw new PlatformException("Published UI config fields cannot be edited; unpublish first: "
                    + uiConfig.getId());
        }
    }
}
