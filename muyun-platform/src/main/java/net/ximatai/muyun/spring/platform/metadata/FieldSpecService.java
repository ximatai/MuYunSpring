package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class FieldSpecService extends AbstractAbilityService<FieldSpec> implements
        SoftDeleteAbility<FieldSpec>,
        EnableAbility<FieldSpec>,
        SortAbility<FieldSpec>,
        ReferenceAbility<FieldSpec>,
        QueryAbility<FieldSpec> {
    public static final String MODULE_ALIAS = "platform.field_spec";
    private final BaseDao<FieldUiControl, String> fieldUiTypeDao;

    public FieldSpecService(BaseDao<FieldSpec, String> fieldTypeDao) {
        this(fieldTypeDao, null);
    }

    @Autowired
    public FieldSpecService(BaseDao<FieldSpec, String> fieldTypeDao,
                                    BaseDao<FieldUiControl, String> fieldUiTypeDao) {
        super(MODULE_ALIAS, FieldSpec.class, fieldTypeDao);
        this.fieldUiTypeDao = fieldUiTypeDao;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, FieldSpec.class, java.util.List.of("id", "alias", "title", "fieldType", "defaultLength", "defaultPrecision", "defaultScale", "defaultQueryOperator", "queryOperators", "defaultUiControlAlias", "uiControlAliases", "safeTargetFieldSpecAliases", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforePrepareInsert(FieldSpec fieldType) {
        if (fieldType.getId() == null || fieldType.getId().isBlank()) {
            fieldType.setId(PlatformNameRules.requireIdentifier(fieldType.getAlias(), "fieldSpecAlias"));
        }
    }

    @Override
    public void beforeInsert(FieldSpec fieldType) {
        normalizeAndValidate(fieldType);
    }

    @Override
    public void beforeUpdate(FieldSpec fieldType) {
        normalizeAndValidate(fieldType);
        FieldSpec existing = selectIncludingDeleted(fieldType.getId());
        rejectChanged(existing, fieldType, "Field spec alias", FieldSpec::getAlias);
    }

    public FieldSpec requireFieldType(String alias) {
        String validAlias = PlatformNameRules.requireIdentifier(alias, "fieldSpecAlias");
        FieldSpec fieldType = findOne(Criteria.of().eq("alias", validAlias));
        if (fieldType == null) {
            throw new PlatformException("Field spec requires existing type: " + validAlias);
        }
        return fieldType;
    }

    private void normalizeAndValidate(FieldSpec fieldType) {
        String alias = PlatformNameRules.requireIdentifier(fieldType.getAlias(), "fieldSpecAlias");
        fieldType.setAlias(alias);
        if (fieldType.getTitle() == null || fieldType.getTitle().isBlank()) {
            fieldType.setTitle(alias);
        }
        if (fieldType.getFieldType() == null) {
            fieldType.setFieldType(FieldType.STRING);
        }
        FieldShapeRules.validate(fieldType.getFieldType(), fieldType.getDefaultLength(),
                fieldType.getDefaultPrecision(), fieldType.getDefaultScale(), alias);
        normalizeQueryDefinition(fieldType);
        normalizeUiControlAliases(fieldType);
        normalizeSafeTargetFieldSpecAliases(fieldType);
        rejectDuplicate(fieldType, Criteria.of().eq("alias", alias),
                "fieldSpecAlias must be unique: " + alias);
    }

    private void normalizeQueryDefinition(FieldSpec fieldType) {
        if (fieldType.getDefaultQueryOperator() == null && (fieldType.getQueryOperators() == null
                || fieldType.getQueryOperators().isEmpty())) {
            return;
        }
        if (fieldType.getDefaultQueryOperator() == null) {
            fieldType.setDefaultQueryOperator(DynamicQueryOperator.defaultOperator(fieldType.getFieldType()));
        }
        if (fieldType.getQueryOperators() == null || fieldType.getQueryOperators().isEmpty()) {
            fieldType.setQueryOperators(DynamicQueryOperator.names(DynamicQueryOperator.defaultOperators(fieldType.getFieldType())));
        } else {
            fieldType.setQueryOperators(DynamicQueryOperator.names(DynamicQueryOperator.parseNames(fieldType.getQueryOperators())));
        }
        fieldType.queryDefinition();
    }

    private void normalizeUiControlAliases(FieldSpec fieldType) {
        if (fieldType.getDefaultUiControlAlias() != null && !fieldType.getDefaultUiControlAlias().isBlank()) {
            fieldType.setDefaultUiControlAlias(PlatformNameRules.requireIdentifier(
                    fieldType.getDefaultUiControlAlias().trim(), "defaultUiControlAlias"));
            requireFieldUiControl(fieldType.getDefaultUiControlAlias());
        }
        if (fieldType.getUiControlAliases() == null || fieldType.getUiControlAliases().isEmpty()) {
            return;
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (String alias : fieldType.getUiControlAliases()) {
            String validAlias = PlatformNameRules.requireIdentifier(alias == null ? null : alias.trim(), "uiTypeAlias");
            requireFieldUiControl(validAlias);
            aliases.add(validAlias);
        }
        if (fieldType.getDefaultUiControlAlias() != null && !aliases.contains(fieldType.getDefaultUiControlAlias())) {
            throw new PlatformException("default UI type must be included in allowed UI types: "
                    + fieldType.getDefaultUiControlAlias());
        }
        fieldType.setUiControlAliases(aliases);
    }

    /** Returns whether a populated entity may change from one specification to another. */
    public boolean allowsDataSafeTarget(String sourceAlias, String targetAlias) {
        String source = PlatformNameRules.requireIdentifier(sourceAlias, "sourceFieldSpecAlias");
        String target = PlatformNameRules.requireIdentifier(targetAlias, "targetFieldSpecAlias");
        if (source.equals(target)) return true;
        Set<String> targets = requireFieldType(source).getSafeTargetFieldSpecAliases();
        return targets != null && targets.contains(target);
    }

    private void normalizeSafeTargetFieldSpecAliases(FieldSpec fieldType) {
        if (fieldType.getSafeTargetFieldSpecAliases() == null || fieldType.getSafeTargetFieldSpecAliases().isEmpty()) {
            fieldType.setSafeTargetFieldSpecAliases(Set.of());
            return;
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (String alias : fieldType.getSafeTargetFieldSpecAliases()) {
            String validAlias = PlatformNameRules.requireIdentifier(
                    alias == null ? null : alias.trim(), "safeTargetFieldSpecAlias");
            if (!validAlias.equals(fieldType.getAlias())) aliases.add(validAlias);
        }
        fieldType.setSafeTargetFieldSpecAliases(aliases);
    }

    private void requireFieldUiControl(String alias) {
        if (fieldUiTypeDao != null
                && fieldUiTypeDao.list(Criteria.of().eq("alias", alias), new PageRequest(0, 1)).isEmpty()) {
            throw new PlatformException("Field spec UI alias requires existing UI type: " + alias);
        }
    }
}
