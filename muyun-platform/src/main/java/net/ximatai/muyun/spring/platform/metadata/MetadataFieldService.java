package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedProtectionAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class MetadataFieldService extends AbstractAbilityService<MetadataField> implements
        SoftDeleteAbility<MetadataField>,
        EnableAbility<MetadataField>,
        SortAbility<MetadataField>,
        PlatformManagedProtectionAbility<MetadataField>,
        QueryAbility<MetadataField> {
    public static final String MODULE_ALIAS = "platform.metadata_field";

    private final ObjectProvider<MetadataFieldReferenceConfigService> referenceConfigServiceProvider;
    private final MetadataService metadataService;
    private final FieldSpecService fieldTypeService;
    private final ObjectProvider<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinatorProvider;
    private final ObjectProvider<PlatformMetadataSchemaEnsureService> schemaEnsureServiceProvider;
    private final ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider;
    private final ObjectProvider<ModuleMetadataRelationService> relationServiceProvider;
    private final ObjectProvider<PlatformModuleService> moduleServiceProvider;
    private final ObjectProvider<MetadataFieldConfigService> fieldConfigServiceProvider;

    public MetadataFieldService(BaseDao<MetadataField, String> fieldDao,
                                MetadataService metadataService,
                                FieldSpecService fieldTypeService,
                                ObjectProvider<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinatorProvider,
                                ObjectProvider<PlatformMetadataSchemaEnsureService> schemaEnsureServiceProvider,
                                ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider,
                                ObjectProvider<ModuleMetadataRelationService> relationServiceProvider,
                                ObjectProvider<PlatformModuleService> moduleServiceProvider,
                                ObjectProvider<MetadataFieldReferenceConfigService> referenceConfigServiceProvider,
                                ObjectProvider<MetadataFieldConfigService> fieldConfigServiceProvider) {
        super(MODULE_ALIAS, MetadataField.class, fieldDao);
        this.referenceConfigServiceProvider = Objects.requireNonNull(referenceConfigServiceProvider, "referenceConfigServiceProvider");
        this.fieldConfigServiceProvider = Objects.requireNonNull(fieldConfigServiceProvider, "fieldConfigServiceProvider");
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.fieldTypeService = Objects.requireNonNull(fieldTypeService, "fieldTypeService");
        this.runtimeRefreshCoordinatorProvider = Objects.requireNonNull(runtimeRefreshCoordinatorProvider,
                "runtimeRefreshCoordinatorProvider must not be null");
        this.schemaEnsureServiceProvider = Objects.requireNonNull(schemaEnsureServiceProvider,
                "schemaEnsureServiceProvider must not be null");
        this.referenceGuardProvider = Objects.requireNonNull(referenceGuardProvider,
                "referenceGuardProvider must not be null");
        this.relationServiceProvider = Objects.requireNonNull(relationServiceProvider,
                "relationServiceProvider must not be null");
        this.moduleServiceProvider = Objects.requireNonNull(moduleServiceProvider,
                "moduleServiceProvider must not be null");
    }

    /** Query eligibility comes from the resolved field specification, never its alias spelling. */
    public boolean supportsQuickSearch(MetadataField field) {
        var type = fieldTypeService.requireFieldType(field.getFieldSpecAlias()).getFieldType();
        return type == net.ximatai.muyun.spring.dynamic.metadata.FieldType.STRING
                || type == net.ximatai.muyun.spring.dynamic.metadata.FieldType.TEXT;
    }

    @Override
    public void beforeDelete(String id) {
        MetadataField field = id == null ? null : select(id);
        assertGovernedMainMetadataWrite(field == null ? null : field.getMetadataId());
        assertNotChildForeignKey(id);
        ConfigurationReferenceDeletionGuard guard = referenceGuardProvider.getIfAvailable();
        if (guard != null) guard.assertCanDelete(ConfigurationReferenceTarget.METADATA_FIELD, id);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, MetadataField.class, java.util.List.of("id", "metadataId", "fieldName", "columnName", "fieldSpecAlias", "fieldOwnership", "fieldForm", "ownerFieldId", "fieldRole", "systemManaged", "required", "uniqueField", "indexed", "sortableField", "titleField", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(MetadataField field) {
        assertGovernedMainMetadataWrite(field == null ? null : field.getMetadataId());
        if (field.getContentPolicy() == null)
            field.setContentPolicy(net.ximatai.muyun.spring.common.content.ContentPolicy.KEEP_ORIGINAL);
        normalizeAndValidate(field);
    }

    @Override
    public void beforeUpdate(MetadataField field) {
        assertGovernedMainMetadataWrite(field == null ? null : field.getMetadataId());
        MetadataField existing = select(field.getId());
        if (field.getContentPolicy() == null)
            field.setContentPolicy(existing == null || existing.getContentPolicy() == null
                    ? net.ximatai.muyun.spring.common.content.ContentPolicy.KEEP_ORIGINAL : existing.getContentPolicy());
        normalizeAndValidate(field);
        validateConfigurationFieldChange(existing, field);
        if (!MetadataFieldPropertyMutationContext.active() && existing != null
                && (!Objects.equals(existing.getFieldSpecAlias(), field.getFieldSpecAlias())
                || Boolean.TRUE.equals(existing.getRequired()) != Boolean.TRUE.equals(field.getRequired()))) {
            fieldConfigServiceProvider.getObject().validateSharedFieldBehavior(field, null, null);
        }
        var references = referenceConfigServiceProvider.getIfAvailable();
        if (references != null) references.validateAffectFieldChange(select(field.getId()), field);
    }

    public void validateConfigurationFieldChange(MetadataField existing, MetadataField proposed) {
        var guard = referenceGuardProvider.getIfAvailable();
        if (guard != null) guard.assertCanChangeField(existing, proposed);
    }

    @Override
    public boolean allowOrdinaryPlatformManagedInsert(MetadataField field) {
        return field != null && (field.getFieldRole() == MetadataFieldRole.MEASURE_BASE_VALUE
                || field.getFieldRole() == MetadataFieldRole.MONEY_BASE_AMOUNT
                || field.getFieldRole() == MetadataFieldRole.MONEY_EXCHANGE_RATE);
    }

    @Override
    public void afterInsert(String id, MetadataField field) {
        PlatformMetadataSchemaEnsureService schemaEnsureService = schemaEnsureService();
        if (schemaEnsureService != null && !MetadataCapabilityGovernanceMutationContext.isActive()) {
            schemaEnsureService.ensure(field.getMetadataId());
        }
    }

    @Override
    public void afterUpdate(MetadataField field, int updated) {
        PlatformMetadataSchemaEnsureService schemaEnsureService = schemaEnsureService();
        if (updated > 0 && schemaEnsureService != null && !MetadataCapabilityGovernanceMutationContext.isActive()) {
            schemaEnsureService.ensure(field.getMetadataId());
        }
    }

    @Override
    public void afterChanged(MetadataField field) {
        PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator = runtimeRefreshCoordinator();
        if (runtimeRefreshCoordinator != null && !MetadataCapabilityGovernanceMutationContext.isActive()) {
            runtimeRefreshCoordinator.refreshByMetadataField(field);
        }
    }

    private PlatformMetadataSchemaEnsureService schemaEnsureService() {
        return schemaEnsureServiceProvider.getIfAvailable();
    }

    private PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator() {
        return runtimeRefreshCoordinatorProvider.getIfAvailable();
    }

    private void normalizeAndValidate(MetadataField field) {
        requireMetadata(field.getMetadataId());
        PlatformNameRules.requireFieldName(field.getFieldName(), "fieldName");
        PlatformNameRules.requireDatabaseName(field.getColumnName(), "columnName");
        field.setFieldSpecAlias(PlatformNameRules.requireIdentifier(field.getFieldSpecAlias(), "fieldSpecAlias"));
        fieldTypeService.requireFieldType(field.getFieldSpecAlias());
        normalizeFieldKind(field);
        if (field.getRequired() == null) {
            field.setRequired(Boolean.FALSE);
        }
        if (field.getUniqueField() == null) {
            field.setUniqueField(Boolean.FALSE);
        }
        if (field.getIndexed() == null) {
            field.setIndexed(Boolean.FALSE);
        }
        if (field.getSortableField() == null) {
            field.setSortableField(Boolean.FALSE);
        }
        if (field.getTitleField() == null) {
            field.setTitleField(Boolean.FALSE);
        }
        if (PlatformFieldPolicy.isAuditLabel(field.getFieldName())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.the-field-name-conflicts-with-a-platform-audit-display-field",
                    "字段名称与平台审计展示字段冲突：{value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
        validateFieldFormBoundary(field);
        validateChildMetadataBoundary(field);
        rejectDuplicateField(field);
        rejectDuplicateSingleFlag(field);
    }

    private void validateChildMetadataBoundary(MetadataField field) {
        ModuleMetadataRelationService relationService = relationServiceProvider.getIfAvailable();
        if (relationService == null || !isChildMetadata(field.getMetadataId(), relationService)) {
            return;
        }
        ModuleMetadataCapabilityPolicy.validateChildField(field);
        for (ModuleMetadataRelation relation : childRelations(field.getMetadataId(), relationService)) {
            if (field.getFieldName().equals(relation.getForeignKey())) {
                ModuleMetadataCapabilityPolicy.validateChildForeignKey(field);
            }
        }
    }

    private void assertNotChildForeignKey(String id) {
        MetadataField field = id == null || id.isBlank() ? null : select(id);
        ModuleMetadataRelationService relationService = relationServiceProvider.getIfAvailable();
        if (field == null || relationService == null) {
            return;
        }
        if (childRelations(field.getMetadataId(), relationService).stream()
                .anyMatch(relation -> field.getFieldName().equals(relation.getForeignKey()))) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.child-relation-foreign-key-cannot-be-deleted",
                    "Child relation foreign key cannot be deleted: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
    }

    private void assertGovernedMainMetadataWrite(String metadataId) {
        if (MetadataCapabilityGovernanceMutationContext.isActive() || metadataId == null || metadataId.isBlank()) return;
        ModuleMetadataRelationService relationService = relationServiceProvider.getIfAvailable();
        PlatformModuleService moduleService = moduleServiceProvider.getIfAvailable();
        if (relationService == null || moduleService == null) return;
        for (ModuleMetadataRelation relation : relationService.list(Criteria.of().eq("metadataId", metadataId)
                .eq("relationRole", RelationRole.MAIN), new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE))) {
            PlatformModule module = moduleService.select(relation.getModuleAlias());
            if (module != null && module.getModuleKind() == ModuleKind.DYNAMIC) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field.dynamic-main-metadata-fields-must-be-changed-through-the-relation-change-set",
                        "Dynamic MAIN metadata fields must be changed through the relation change-set publisher: {value1}"), Map.of("value1", String.valueOf(metadataId)));
            }
        }
    }

    private boolean isChildMetadata(String metadataId, ModuleMetadataRelationService relationService) {
        return !childRelations(metadataId, relationService).isEmpty();
    }

    private java.util.List<ModuleMetadataRelation> childRelations(String metadataId,
                                                                    ModuleMetadataRelationService relationService) {
        return relationService.list(Criteria.of().eq("metadataId", metadataId)
                        .eq("relationRole", RelationRole.CHILD),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE));
    }

    private void normalizeFieldKind(MetadataField field) {
        if (field.getFieldOwnership() == null) {
            field.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
        }
        if (field.getFieldForm() == null) {
            field.setFieldForm(MetadataFieldForm.PHYSICAL);
        }
        if (field.getSystemManaged() == null) {
            field.setSystemManaged(Boolean.FALSE);
        }
        if (field.getOwnerFieldId() != null && field.getOwnerFieldId().isBlank()) {
            field.setOwnerFieldId(null);
        }
        if (field.getFieldForm() == MetadataFieldForm.COMPANION || field.getFieldForm() == MetadataFieldForm.SHADOW) {
            requireOwnerField(field);
        } else if (field.getOwnerFieldId() != null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.ownerfieldid-is-only-allowed-for-companion-or-shadow-field",
                    "ownerFieldId is only allowed for companion or shadow field: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
        if (field.getFieldRole() == MetadataFieldRole.TIME_ZONE && field.getFieldForm() != MetadataFieldForm.COMPANION) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.time-zone-field-role-requires-companion-field",
                    "TIME_ZONE field role requires companion field: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
        if (field.getFieldRole() == MetadataFieldRole.SIGNATURE && field.getFieldForm() != MetadataFieldForm.COMPANION) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.signature-field-role-requires-companion-field",
                    "SIGNATURE field role requires companion field: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
        if (field.getFieldRole() == MetadataFieldRole.MEASURE_UNIT
                && field.getFieldForm() != MetadataFieldForm.COMPANION) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.measure-unit-field-role-requires-companion-field",
                    "MEASURE_UNIT field role requires companion field: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
        if (field.getFieldRole() == MetadataFieldRole.MEASURE_BASE_VALUE) {
            if (field.getFieldForm() != MetadataFieldForm.SHADOW) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field.measure-base-value-field-role-requires-shadow-field",
                        "MEASURE_BASE_VALUE field role requires shadow field: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
            }
            field.setSystemManaged(Boolean.TRUE);
        }
    }

    private void validateFieldFormBoundary(MetadataField field) {
        if (field.getFieldForm() != MetadataFieldForm.VIRTUAL) {
            return;
        }
        if (Boolean.TRUE.equals(field.getRequired())
                || Boolean.TRUE.equals(field.getUniqueField())
                || Boolean.TRUE.equals(field.getIndexed())
                || Boolean.TRUE.equals(field.getSortableField())
                || Boolean.TRUE.equals(field.getTitleField())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.virtual-metadata-field-cannot-be-required-unique-indexed-sortable-or-title-field",
                    "Virtual metadata field cannot be required, unique, indexed, sortable or title field: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
    }

    private void requireOwnerField(MetadataField field) {
        MetadataField owner = field.getOwnerFieldId() == null ? null : select(field.getOwnerFieldId());
        if (owner == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.metadata-field-requires-owner-field",
                    "metadata field requires owner field: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
        if (!Objects.equals(owner.getMetadataId(), field.getMetadataId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.owner-field-must-belong-to-same-metadata",
                    "owner field must belong to same metadata: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
        if (Objects.equals(owner.getId(), field.getId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.metadata-field-cannot-own-itself",
                    "metadata field cannot own itself: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
    }

    private void rejectDuplicateField(MetadataField field) {
        rejectDuplicate(field, Criteria.of()
                        .eq("metadataId", field.getMetadataId())
                        .eq("fieldName", field.getFieldName()), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.metadata-field.metadata-fieldname-must-be-unique",
                                "metadata fieldName must be unique: {value1}"), Map.of("value1", String.valueOf(field.getFieldName()))));
        rejectDuplicate(field, Criteria.of()
                        .eq("metadataId", field.getMetadataId())
                        .eq("columnName", field.getColumnName()), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.metadata-field.metadata-columnname-must-be-unique",
                                "metadata columnName must be unique: {value1}"), Map.of("value1", String.valueOf(field.getColumnName()))));
    }

    private void rejectDuplicateSingleFlag(MetadataField field) {
        if (Boolean.TRUE.equals(field.getTitleField()) && existsOtherInCurrentScope(field, Criteria.of()
                .eq("metadataId", field.getMetadataId())
                .eq("titleField", Boolean.TRUE))) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.metadata-can-only-have-one-title-field",
                    "metadata can only have one title field: {value1}"), Map.of("value1", String.valueOf(field.getMetadataId())));
        }
        if (Boolean.TRUE.equals(field.getSortableField()) && existsOtherInCurrentScope(field, Criteria.of()
                .eq("metadataId", field.getMetadataId())
                .eq("sortableField", Boolean.TRUE))) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.metadata-can-only-have-one-sortable-field",
                    "metadata can only have one sortable field: {value1}"), Map.of("value1", String.valueOf(field.getMetadataId())));
        }
    }

    private Metadata requireMetadata(String metadataId) {
        Metadata metadata = metadataId == null || metadataId.isBlank() ? null : metadataService.select(metadataId);
        if (metadata == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field.metadata-field-requires-existing-metadata",
                    "Metadata field requires existing metadata: {value1}"), Map.of("value1", String.valueOf(metadataId)));
        }
        return metadata;
    }

}
