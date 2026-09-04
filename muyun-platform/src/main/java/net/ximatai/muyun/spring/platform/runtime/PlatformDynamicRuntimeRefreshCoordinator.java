package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRefreshResult;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class PlatformDynamicRuntimeRefreshCoordinator {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ObjectProvider<PlatformDynamicRuntimeRefreshService> refreshServiceProvider;
    private final ObjectProvider<ModuleMetadataRelationService> relationServiceProvider;
    private final ObjectProvider<ModuleMetadataFieldService> moduleFieldServiceProvider;
    private final ObjectProvider<MetadataViewService> viewServiceProvider;

    public PlatformDynamicRuntimeRefreshCoordinator(
            PlatformDynamicRuntimeRefreshService refreshService,
            ObjectProvider<ModuleMetadataRelationService> relationServiceProvider,
            ObjectProvider<ModuleMetadataFieldService> moduleFieldServiceProvider,
            ObjectProvider<MetadataViewService> viewServiceProvider) {
        this(provider(refreshService), relationServiceProvider, moduleFieldServiceProvider, viewServiceProvider);
    }

    @Autowired
    public PlatformDynamicRuntimeRefreshCoordinator(
            ObjectProvider<PlatformDynamicRuntimeRefreshService> refreshServiceProvider,
            ObjectProvider<ModuleMetadataRelationService> relationServiceProvider,
            ObjectProvider<ModuleMetadataFieldService> moduleFieldServiceProvider,
            ObjectProvider<MetadataViewService> viewServiceProvider) {
        this.refreshServiceProvider = Objects.requireNonNull(refreshServiceProvider,
                "refreshServiceProvider must not be null");
        this.relationServiceProvider = Objects.requireNonNull(relationServiceProvider,
                "relationServiceProvider must not be null");
        this.moduleFieldServiceProvider = Objects.requireNonNull(moduleFieldServiceProvider,
                "moduleFieldServiceProvider must not be null");
        this.viewServiceProvider = Objects.requireNonNull(viewServiceProvider, "viewServiceProvider must not be null");
    }

    public PlatformDynamicRuntimeRefreshCoordinator(
            PlatformDynamicRuntimeRefreshService refreshService,
            ModuleMetadataRelationService relationService,
            ModuleMetadataFieldService moduleFieldService,
            MetadataViewService viewService) {
        this(refreshService, provider(relationService), provider(moduleFieldService), provider(viewService));
    }

    public List<DynamicModuleRefreshResult> refreshModule(String moduleAlias) {
        return refreshModules(List.of(PlatformNameRules.requireModuleAlias(moduleAlias)));
    }

    public List<DynamicModuleRefreshResult> refreshByRelation(ModuleMetadataRelation relation) {
        if (relation == null) {
            return List.of();
        }
        return refreshModule(relation.getModuleAlias());
    }

    public List<DynamicModuleRefreshResult> refreshByModuleField(ModuleMetadataField moduleField) {
        if (moduleField == null) {
            return List.of();
        }
        return refreshByRelationId(moduleField.getRelationId());
    }

    public List<DynamicModuleRefreshResult> refreshByFieldFilter(ModuleMetadataFieldFilter filter) {
        if (filter == null) {
            return List.of();
        }
        return refreshByModuleFieldId(filter.getModuleMetadataFieldId());
    }

    public List<DynamicModuleRefreshResult> refreshByFieldAffect(ModuleMetadataFieldAffect affect) {
        if (affect == null) {
            return List.of();
        }
        return refreshByModuleFieldId(affect.getModuleMetadataFieldId());
    }

    public List<DynamicModuleRefreshResult> refreshByFormulaRule(ModuleMetadataFormulaRule rule) {
        if (rule == null) {
            return List.of();
        }
        return refreshByRelationId(rule.getRelationId());
    }

    public List<DynamicModuleRefreshResult> refreshByMetadataView(MetadataView view) {
        if (view == null) {
            return List.of();
        }
        return refreshByRelationId(view.getRelationId());
    }

    public List<DynamicModuleRefreshResult> refreshByMetadataViewField(MetadataViewField viewField) {
        if (viewField == null) {
            return List.of();
        }
        MetadataView view = requireView(viewField.getViewId());
        return refreshByMetadataView(view);
    }

    public List<DynamicModuleRefreshResult> refreshByModuleAction(PlatformModuleAction action) {
        if (action == null) {
            return List.of();
        }
        return refreshModule(action.getModuleAlias());
    }

    public List<DynamicModuleRefreshResult> refreshByMetadataField(MetadataField field) {
        if (field == null) {
            return List.of();
        }
        return refreshByMetadataId(field.getMetadataId());
    }

    public List<DynamicModuleRefreshResult> refreshByMetadataId(String metadataId) {
        if (metadataId == null || metadataId.isBlank()) {
            return List.of();
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
        return refreshModules(moduleAliases);
    }

    /** Synchronously activates affected module snapshots; callers must already be after commit. */
    public List<DynamicModuleRefreshResult> activateByMetadataIdNow(String metadataId) {
        if (metadataId == null || metadataId.isBlank()) return List.of();
        Set<String> moduleAliases = new LinkedHashSet<>();
        for (ModuleMetadataRelation relation : relationService().list(Criteria.of().eq("metadataId", metadataId),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD))) {
            if (relation.getModuleAlias() != null && !relation.getModuleAlias().isBlank()) {
                moduleAliases.add(PlatformNameRules.requireModuleAlias(relation.getModuleAlias()));
            }
        }
        List<DynamicModuleRefreshResult> results = new ArrayList<>();
        for (String moduleAlias : moduleAliases) results.add(refreshService().activateNow(moduleAlias));
        return results;
    }

    /** Synchronously activates a deduplicated module set; callers must already be after commit. */
    public List<DynamicModuleRefreshResult> activateModulesNow(Iterable<String> moduleAliases) {
        Set<String> distinctAliases = new LinkedHashSet<>();
        for (String moduleAlias : moduleAliases) {
            if (moduleAlias != null && !moduleAlias.isBlank()) {
                distinctAliases.add(PlatformNameRules.requireModuleAlias(moduleAlias));
            }
        }
        List<DynamicModuleRefreshResult> results = new ArrayList<>();
        for (String moduleAlias : distinctAliases) results.add(refreshService().activateNow(moduleAlias));
        return results;
    }

    /** Removes active runtime projections for modules whose MAIN metadata was deleted. */
    public void deactivateModulesNow(Iterable<String> moduleAliases) {
        Set<String> distinctAliases = new LinkedHashSet<>();
        for (String moduleAlias : moduleAliases) {
            if (moduleAlias != null && !moduleAlias.isBlank()) {
                distinctAliases.add(PlatformNameRules.requireModuleAlias(moduleAlias));
            }
        }
        for (String moduleAlias : distinctAliases) refreshService().deactivateNow(moduleAlias);
    }

    public List<DynamicModuleRefreshResult> refreshByRelationId(String relationId) {
        ModuleMetadataRelation relation = requireRelation(relationId);
        return refreshByRelation(relation);
    }

    public List<DynamicModuleRefreshResult> refreshByModuleFieldId(String moduleFieldId) {
        ModuleMetadataField moduleField = requireModuleField(moduleFieldId);
        return refreshByModuleField(moduleField);
    }

    private List<DynamicModuleRefreshResult> refreshModules(Iterable<String> moduleAliases) {
        Set<String> distinctAliases = new LinkedHashSet<>();
        for (String moduleAlias : moduleAliases) {
            if (moduleAlias != null && !moduleAlias.isBlank()) {
                distinctAliases.add(PlatformNameRules.requireModuleAlias(moduleAlias));
            }
        }
        List<DynamicModuleRefreshResult> results = new ArrayList<>();
        TransactionScopeSupport.afterCommitOrNow(() -> {
            for (String moduleAlias : distinctAliases) {
                results.add(refreshService().refresh(moduleAlias));
            }
        });
        return results;
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

    private PlatformDynamicRuntimeRefreshService refreshService() {
        return refreshServiceProvider.getObject();
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
