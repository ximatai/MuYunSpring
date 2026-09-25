package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class ModuleMetadataFieldFilterService extends AbstractAbilityService<ModuleMetadataFieldFilter> implements
        SoftDeleteAbility<ModuleMetadataFieldFilter>,
        SortAbility<ModuleMetadataFieldFilter>,
        QueryAbility<ModuleMetadataFieldFilter> {
    public static final String MODULE_ALIAS = "platform.module_metadata_field_filter";

    private final ModuleMetadataFieldService moduleFieldService;
    private final PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator;

    public ModuleMetadataFieldFilterService(BaseDao<ModuleMetadataFieldFilter, String> filterDao,
                                            ModuleMetadataFieldService moduleFieldService,
                                            Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        super(MODULE_ALIAS, ModuleMetadataFieldFilter.class, filterDao);
        this.moduleFieldService = moduleFieldService;
        this.runtimeRefreshCoordinator = runtimeRefreshCoordinator.orElse(null);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, ModuleMetadataFieldFilter.class, java.util.List.of("id", "moduleMetadataFieldId", "formFieldId", "referenceFieldId", "operator", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(ModuleMetadataFieldFilter filter) {
        normalizeAndValidate(filter);
    }

    @Override
    public void beforeUpdate(ModuleMetadataFieldFilter filter) {
        normalizeAndValidate(filter);
    }

    @Override
    public void afterChanged(ModuleMetadataFieldFilter filter) {
        if (runtimeRefreshCoordinator != null) {
            runtimeRefreshCoordinator.refreshByFieldFilter(filter);
        }
    }

    private void normalizeAndValidate(ModuleMetadataFieldFilter filter) {
        ModuleMetadataField owner = requireModuleField(filter.getModuleMetadataFieldId(), "moduleMetadataFieldId");
        ModuleMetadataField formField = requireModuleField(filter.getFormFieldId(), "formFieldId");
        ModuleMetadataField referenceField = requireModuleField(filter.getReferenceFieldId(), "referenceFieldId");
        if (!Objects.equals(owner.getRelationId(), formField.getRelationId())) {
            throw new PlatformException("reference filter form field must belong to owner relation");
        }
        validateReferenceField(owner, referenceField);
        filter.setModuleMetadataFieldId(owner.getId());
        filter.setFormFieldId(formField.getId());
        filter.setReferenceFieldId(referenceField.getId());
    }

    private void validateReferenceField(ModuleMetadataField owner, ModuleMetadataField referenceField) {
        if (owner.getReferenceModuleAlias() == null || owner.getReferenceModuleAlias().isBlank()) {
            throw new PlatformException("reference filter requires owner referenceModuleAlias");
        }
        ResolvedModuleMetadataField resolved = moduleFieldService.resolve(referenceField.getId());
        if (!owner.getReferenceModuleAlias().equals(resolved.moduleAlias())) {
            throw new PlatformException("reference filter field must belong to reference module: "
                    + owner.getReferenceModuleAlias());
        }
        if (resolved.relationRole() != RelationRole.MAIN) {
            throw new PlatformException("reference filter field must belong to reference module main relation");
        }
    }

    private ModuleMetadataField requireModuleField(String moduleMetadataFieldId, String label) {
        ModuleMetadataField moduleField = moduleMetadataFieldId == null || moduleMetadataFieldId.isBlank()
                ? null
                : moduleFieldService.select(moduleMetadataFieldId);
        if (moduleField == null) {
            throw new PlatformException("Module metadata field filter requires existing " + label + ": "
                    + moduleMetadataFieldId);
        }
        return moduleField;
    }
}
