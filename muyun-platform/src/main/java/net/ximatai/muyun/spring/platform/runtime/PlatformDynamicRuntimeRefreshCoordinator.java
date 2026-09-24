package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataView;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewField;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffect;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilter;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRule;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class PlatformDynamicRuntimeRefreshCoordinator {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ObjectProvider<DynamicRuntimeActivationService> activationProvider;
    private final ObjectProvider<ModuleMetadataRelationService> relationServiceProvider;
    private final ObjectProvider<ModuleMetadataFieldService> moduleFieldServiceProvider;
    private final ObjectProvider<MetadataViewService> viewServiceProvider;

    public PlatformDynamicRuntimeRefreshCoordinator(
            DynamicRuntimeActivationService activation,
            ObjectProvider<ModuleMetadataRelationService> relationServiceProvider,
            ObjectProvider<ModuleMetadataFieldService> moduleFieldServiceProvider,
            ObjectProvider<MetadataViewService> viewServiceProvider) {
        this(provider(activation), relationServiceProvider, moduleFieldServiceProvider, viewServiceProvider);
    }

    @Autowired
    public PlatformDynamicRuntimeRefreshCoordinator(
            ObjectProvider<DynamicRuntimeActivationService> activationProvider,
            ObjectProvider<ModuleMetadataRelationService> relationServiceProvider,
            ObjectProvider<ModuleMetadataFieldService> moduleFieldServiceProvider,
            ObjectProvider<MetadataViewService> viewServiceProvider) {
        this.activationProvider = Objects.requireNonNull(activationProvider,
                "activationProvider must not be null");
        this.relationServiceProvider = Objects.requireNonNull(relationServiceProvider,
                "relationServiceProvider must not be null");
        this.moduleFieldServiceProvider = Objects.requireNonNull(moduleFieldServiceProvider,
                "moduleFieldServiceProvider must not be null");
        this.viewServiceProvider = Objects.requireNonNull(viewServiceProvider, "viewServiceProvider must not be null");
    }

    public PlatformDynamicRuntimeRefreshCoordinator(
            DynamicRuntimeActivationService activation,
            ModuleMetadataRelationService relationService,
            ModuleMetadataFieldService moduleFieldService,
            MetadataViewService viewService) {
        this(activation, provider(relationService), provider(moduleFieldService), provider(viewService));
    }

    public void refreshModule(String moduleAlias) {
        refreshModules(List.of(PlatformNameRules.requireModuleAlias(moduleAlias)));
    }

    /** Action catalogues exist before MAIN metadata; compile only once the module is configured. */
    public void refreshConfiguredModule(String moduleAlias) {
        String alias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (!relationService().list(Criteria.of().eq("moduleAlias", alias)
                .eq("relationRole", RelationRole.MAIN).isNull("tenantId"), new PageRequest(0, 1)).isEmpty()) {
            activation().schedule(alias);
        }
    }

    public void refreshByRelation(ModuleMetadataRelation relation) {
        if (relation == null) {
            return;
        }
        refreshModule(relation.getModuleAlias());
    }

    public void refreshByModuleField(ModuleMetadataField moduleField) {
        if (moduleField == null) {
            return;
        }
        refreshByRelationId(moduleField.getRelationId());
    }

    public void refreshByFieldFilter(ModuleMetadataFieldFilter filter) {
        if (filter == null) {
            return;
        }
        refreshByModuleFieldId(filter.getModuleMetadataFieldId());
    }

    public void refreshByFieldAffect(ModuleMetadataFieldAffect affect) {
        if (affect == null) {
            return;
        }
        refreshByModuleFieldId(affect.getModuleMetadataFieldId());
    }

    public void refreshByFormulaRule(ModuleMetadataFormulaRule rule) {
        if (rule == null) {
            return;
        }
        refreshByRelationId(rule.getRelationId());
    }

    public void refreshByMetadataView(MetadataView view) {
        if (view == null) {
            return;
        }
        refreshByRelationId(view.getRelationId());
    }

    public void refreshByMetadataViewField(MetadataViewField viewField) {
        if (viewField == null) {
            return;
        }
        MetadataView view = requireView(viewField.getViewId());
        refreshByMetadataView(view);
    }

    public void refreshByModuleAction(PlatformModuleAction action) {
        if (action == null) {
            return;
        }
        refreshModule(action.getModuleAlias());
    }

    public void refreshByMetadataField(MetadataField field) {
        if (field == null) {
            return;
        }
        refreshByMetadataId(field.getMetadataId());
    }

    public void refreshByMetadataId(String metadataId) {
        if (metadataId == null || metadataId.isBlank()) {
            return;
        }
        Set<String> moduleAliases = new LinkedHashSet<>();
        for (ModuleMetadataRelation relation : relationService().list(
                Criteria.of().eq("metadataId", metadataId),
                ALL,
                Sort.asc(PlatformAbilityFields.SORT_FIELD))) {
            if (relation.getModuleAlias() != null && !relation.getModuleAlias().isBlank()) {
                moduleAliases.add(relation.getModuleAlias());
            }
        }
        refreshModules(moduleAliases);
    }

    /** Registers intent in the same transaction as the metadata change. */
    public void scheduleByMetadataId(String metadataId) {
        refreshByMetadataId(metadataId);
    }

    public void scheduleModules(Iterable<String> moduleAliases) {
        refreshModules(moduleAliases);
    }

    public void refreshByRelationId(String relationId) {
        ModuleMetadataRelation relation = requireRelation(relationId);
        refreshByRelation(relation);
    }

    public void refreshByModuleFieldId(String moduleFieldId) {
        ModuleMetadataField moduleField = requireModuleField(moduleFieldId);
        refreshByModuleField(moduleField);
    }

    private void refreshModules(Iterable<String> moduleAliases) {
        Set<String> distinctAliases = new LinkedHashSet<>();
        for (String moduleAlias : moduleAliases) {
            if (moduleAlias != null && !moduleAlias.isBlank()) {
                distinctAliases.add(PlatformNameRules.requireModuleAlias(moduleAlias));
            }
        }
        distinctAliases.stream().sorted().forEach(alias -> activation().schedule(alias));
    }

    private ModuleMetadataRelation requireRelation(String relationId) {
        ModuleMetadataRelation relation = relationId == null || relationId.isBlank()
                ? null
                : relationService().select(relationId);
        if (relation == null) {
            throw new PlatformException("Runtime refresh requires existing module metadata relation: " + relationId);
        }
        return relation;
    }

    private ModuleMetadataField requireModuleField(String moduleFieldId) {
        ModuleMetadataField moduleField = moduleFieldId == null || moduleFieldId.isBlank()
                ? null
                : moduleFieldService().select(moduleFieldId);
        if (moduleField == null) {
            throw new PlatformException("Runtime refresh requires existing module metadata field: " + moduleFieldId);
        }
        return moduleField;
    }

    private MetadataView requireView(String viewId) {
        MetadataView view = viewId == null || viewId.isBlank() ? null : viewService().select(viewId);
        if (view == null) {
            throw new PlatformException("Runtime refresh requires existing metadata view: " + viewId);
        }
        return view;
    }

    private ModuleMetadataRelationService relationService() {
        return relationServiceProvider.getObject();
    }

    private ModuleMetadataFieldService moduleFieldService() {
        return moduleFieldServiceProvider.getObject();
    }

    private MetadataViewService viewService() {
        return viewServiceProvider.getObject();
    }

    private DynamicRuntimeActivationService activation() {
        return activationProvider.getObject();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
