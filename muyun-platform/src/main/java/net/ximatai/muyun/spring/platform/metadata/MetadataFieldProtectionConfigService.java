package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.security.FieldSignatureMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class MetadataFieldProtectionConfigService extends AbstractAbilityService<MetadataFieldProtectionConfig> implements
        SoftDeleteAbility<MetadataFieldProtectionConfig>,
        QueryAbility<MetadataFieldProtectionConfig> {
    public static final String MODULE_ALIAS = "platform.metadata_field_protection_config";

    private final MetadataFieldService fieldService;
    private final FieldSpecService fieldTypeService;
    private final BaseDao<MetadataFieldConfig, String> fieldConfigDao;
    private final Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator;

    public MetadataFieldProtectionConfigService(BaseDao<MetadataFieldProtectionConfig, String> configDao,
                                                MetadataFieldService fieldService,
                                                FieldSpecService fieldTypeService,
                                                BaseDao<MetadataFieldConfig, String> fieldConfigDao,
                                                Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        super(MODULE_ALIAS, MetadataFieldProtectionConfig.class, configDao);
        this.fieldService = Objects.requireNonNull(fieldService, "fieldService must not be null");
        this.fieldTypeService = Objects.requireNonNull(fieldTypeService, "fieldTypeService must not be null");
        this.fieldConfigDao = Objects.requireNonNull(fieldConfigDao, "fieldConfigDao must not be null");
        this.runtimeRefreshCoordinator = Objects.requireNonNull(runtimeRefreshCoordinator,
                "runtimeRefreshCoordinator must not be null");
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, MetadataFieldProtectionConfig.class, java.util.List.of("id", "metadataFieldId", "enabled", "encryptionMode", "signatureMode", "maskingPolicy", "createdAt", "updatedAt"));
    }

    @Override
    public void beforeInsert(MetadataFieldProtectionConfig config) {
        normalizeAndValidate(config);
    }

    @Override
    public void beforeUpdate(MetadataFieldProtectionConfig config) {
        normalizeAndValidate(config);
    }

    @Override
    public void afterChanged(MetadataFieldProtectionConfig config) {
        PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator =
                this.runtimeRefreshCoordinator.orElse(null);
        if (runtimeRefreshCoordinator == null || config.getMetadataFieldId() == null
                || config.getMetadataFieldId().isBlank()) {
            return;
        }
        MetadataField field = fieldService.select(config.getMetadataFieldId());
        if (field != null) {
            runtimeRefreshCoordinator.refreshByMetadataField(field);
        }
    }

    public MetadataFieldProtectionConfig findByMetadataFieldId(String metadataFieldId) {
        if (metadataFieldId == null || metadataFieldId.isBlank()) {
            return null;
        }
        return findOne(Criteria.of().eq("metadataFieldId", metadataFieldId));
    }

    public FieldProtectionDefinition definition(String metadataFieldId) {
        MetadataFieldProtectionConfig config = findByMetadataFieldId(metadataFieldId);
        return config == null ? FieldProtectionDefinition.NONE : config.definition();
    }

    private void normalizeAndValidate(MetadataFieldProtectionConfig config) {
        MetadataField field = requireField(config.getMetadataFieldId());
        if (config.getEnabled() == null) {
            config.setEnabled(Boolean.TRUE);
        }
        if (config.getEncryptionMode() == null) {
            config.setEncryptionMode(FieldEncryptionMode.NONE);
        }
        if (config.getSignatureMode() == null) {
            config.setSignatureMode(FieldSignatureMode.NONE);
        }
        if (config.getMaskingPolicy() == null) {
            config.setMaskingPolicy(FieldMaskingPolicy.NONE);
        }
        FieldProtectionDefinition definition = config.definition();
        if (definition.enabled()) {
            validateFieldShape(field, definition);
            validateFieldConfig(field, definition);
        }
        rejectDuplicate(config, Criteria.of().eq("metadataFieldId", config.getMetadataFieldId()),
                "metadata field protection config must be unique: " + config.getMetadataFieldId());
    }

    private void validateFieldShape(MetadataField field, FieldProtectionDefinition definition) {
        if (definition.hasStorageProtection() && field.getFieldForm() == MetadataFieldForm.VIRTUAL) {
            throw new PlatformException("Virtual metadata field cannot use storage protection: " + field.getId());
        }
        FieldSpec fieldType = fieldTypeService.requireFieldType(field.getFieldSpecAlias());
        if (definition.hasStorageProtection()
                && fieldType.getFieldType() != FieldType.STRING
                && fieldType.getFieldType() != FieldType.TEXT) {
            throw new PlatformException("Field storage protection currently requires string field: " + field.getId());
        }
        if (definition.hasStorageProtection()
                && (Boolean.TRUE.equals(field.getUniqueField())
                || Boolean.TRUE.equals(field.getIndexed())
                || Boolean.TRUE.equals(field.getSortableField())
                || Boolean.TRUE.equals(field.getTitleField()))) {
            throw new PlatformException("Protected storage field cannot be unique, indexed, sortable or title field: "
                    + field.getId());
        }
    }

    private void validateFieldConfig(MetadataField field, FieldProtectionDefinition definition) {
        if (!definition.hasStorageProtection()) {
            return;
        }
        FieldSpec fieldType = fieldTypeService.requireFieldType(field.getFieldSpecAlias());
        // Use the same active/tenant scope as field configuration reads without a service dependency cycle.
        var configs = fieldConfigDao.list(activeCriteria(Criteria.of().eq("metadataFieldId", field.getId())));
        MetadataFieldConfig defaultConfig = configs.stream().filter(config -> config.getRelationId() == null)
                .findFirst().orElse(null);
        if (MetadataFieldConfig.effectiveQueryDefinition(fieldType, defaultConfig, null).queryable()
                || configs.stream().filter(config -> config.getRelationId() != null)
                .anyMatch(config -> MetadataFieldConfig.effectiveQueryDefinition(fieldType, defaultConfig, config).queryable())) {
            throw new PlatformException("Protected storage field cannot be queryable: " + field.getId());
        }
    }

    @Override
    public void beforeRestore(String id) {
        MetadataFieldProtectionConfig config = selectIgnoreSoftDelete(id);
        if (config != null) normalizeAndValidate(config);
    }

    private MetadataField requireField(String metadataFieldId) {
        MetadataField field = metadataFieldId == null || metadataFieldId.isBlank() ? null : fieldService.select(metadataFieldId);
        if (field == null) {
            throw new PlatformException("Field protection config requires existing metadata field: " + metadataFieldId);
        }
        return field;
    }
}
