package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.List;
import java.util.function.Supplier;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class PlatformUiSetService extends AbstractAbilityService<PlatformUiSet> implements
        SoftDeleteAbility<PlatformUiSet>,
        EnableAbility<PlatformUiSet>,
        SortAbility<PlatformUiSet>,
        QueryAbility<PlatformUiSet> {
    public static final String MODULE_ALIAS = "platform.ui_set";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final PlatformModuleService moduleService;
    private final Supplier<PlatformUiConfigService> uiConfigServiceProvider;

    public PlatformUiSetService(BaseDao<PlatformUiSet, String> uiSetDao,
                                PlatformModuleService moduleService) {
        this(uiSetDao, moduleService, () -> null);
    }

    @Autowired
    public PlatformUiSetService(BaseDao<PlatformUiSet, String> uiSetDao,
                                PlatformModuleService moduleService,
                                ObjectProvider<PlatformUiConfigService> uiConfigServiceProvider) {
        this(uiSetDao, moduleService, uiConfigServiceProvider::getIfAvailable);
    }

    PlatformUiSetService(BaseDao<PlatformUiSet, String> uiSetDao,
                         PlatformModuleService moduleService,
                         Supplier<PlatformUiConfigService> uiConfigServiceProvider) {
        super(MODULE_ALIAS, PlatformUiSet.class, uiSetDao);
        this.moduleService = moduleService;
        this.uiConfigServiceProvider = uiConfigServiceProvider;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformUiSet.class, java.util.List.of("alias", "title", "setType", "defaultSet", "enabled"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("alias"));
    }

    @Override
    public void beforeInsert(PlatformUiSet uiSet) {
        normalizeAndValidate(uiSet);
    }

    @Override
    public void beforeUpdate(PlatformUiSet uiSet) {
        rejectMutationWhenPublishedConfigExists(uiSet.getId());
        normalizeAndValidate(uiSet);
        PlatformUiSet existing = selectIncludingDeleted(uiSet.getId());
        rejectChangedText(existing, uiSet, TextReference.code(
                "platform.rejection.platform-ui-set.ui-set-modulealias-cannot-be-changed",
                "UI set moduleAlias cannot be changed"), PlatformUiSet::getModuleAlias);
        rejectChangedText(existing, uiSet, TextReference.code(
                "platform.rejection.platform-ui-set.ui-set-alias-cannot-be-changed",
                "UI set alias cannot be changed"), PlatformUiSet::getAlias);
    }

    @Override
    public void beforeDelete(String id) {
        PlatformUiConfigService uiConfigService = uiConfigServiceProvider.get();
        if (uiConfigService != null && uiConfigService.hasActiveConfigForUiSet(id)) {
            throw BusinessExceptions.warningText("platform.ui-set.configs-exist", TextReference.code(
                    "platform.rejection.platform-ui-set.ui-set-cannot-be-deleted-while-ui-configs-exist",
                    "UI set cannot be deleted while UI configs exist: {value1}"), Map.of("value1", String.valueOf(id)));
        }
    }

    /** Resolves a page-consumption reference without granting access through the editing API. */
    public PlatformUiSet requireVisibleUiSet(String id) {
        PlatformUiSet uiSet = PlatformPageConfigReadScope.findVisible(this, id);
        if (uiSet == null) {
            throw BusinessExceptions.warningText("platform.ui-set.not-found", TextReference.code(
                    "platform.rejection.platform-ui-set.ui-set-requires-existing-config",
                    "UI set requires existing config: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        return uiSet;
    }

    public PlatformUiSet requireUiSet(String id) {
        PlatformUiSet uiSet = id == null || id.isBlank() ? null : select(id);
        if (uiSet == null) {
            throw BusinessExceptions.warningText("platform.ui-set.not-found", TextReference.code(
                    "platform.rejection.platform-ui-set.ui-set-requires-existing-config",
                    "UI set requires existing config: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        return uiSet;
    }

    /** Returns one module's enabled UI sets in the same order used by page composition. */
    public List<PlatformUiSet> listByModuleAlias(String moduleAlias) {
        if (moduleAlias == null || moduleAlias.isBlank()) {
            return List.of();
        }
        return list(enabledCriteria(Criteria.of().eq("moduleAlias", moduleAlias.trim())), ALL,
                Sort.asc("sortOrder"));
    }

    private void normalizeAndValidate(PlatformUiSet uiSet) {
        String moduleAlias = PlatformNameRules.requireModuleAlias(uiSet.getModuleAlias());
        PlatformModule module = moduleService.resolveVisibleModule(moduleAlias);
        if (module == null) {
            throw BusinessExceptions.warningText("platform.ui-set.module-not-found", TextReference.code(
                    "platform.rejection.platform-ui-set.ui-set-requires-existing-module",
                    "UI set requires existing module: {value1}"), Map.of("value1", String.valueOf(moduleAlias)));
        }
        String alias = PlatformNameRules.requireIdentifier(uiSet.getAlias(), "uiSetAlias");
        uiSet.setModuleAlias(moduleAlias);
        uiSet.setAlias(alias);
        if (uiSet.getSetType() == null) {
            throw BusinessExceptions.warningText("platform.ui-set.type-required", TextReference.code(
                    "platform.rejection.platform-ui-set.ui-set-type-must-not-be-null",
                    "UI set type must not be null"));
        }
        if (uiSet.getTitle() == null || uiSet.getTitle().isBlank()) {
            uiSet.setTitle(alias);
        }
        if (uiSet.getDefaultSet() == null) {
            uiSet.setDefaultSet(Boolean.FALSE);
        }
        rejectDuplicate(uiSet, Criteria.of()
                        .eq("moduleAlias", moduleAlias)
                        .eq("alias", alias), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.platform-ui-set.ui-set-alias-must-be-unique-in-module",
                                "UI set alias must be unique in module: {value1}.{value2}"), Map.of("value1", String.valueOf(moduleAlias), "value2", String.valueOf(alias))));
        if (Boolean.TRUE.equals(uiSet.getDefaultSet())) {
            rejectDuplicate(uiSet, Criteria.of()
                            .eq("moduleAlias", moduleAlias)
                            .eq("setType", uiSet.getSetType())
                            .eq("defaultSet", Boolean.TRUE), () -> PlatformErrors.badRequestText(TextReference.code(
                                    "platform.rejection.platform-ui-set.only-one-default-ui-set-is-allowed-for-module-and-type",
                                    "Only one default UI set is allowed for module and type: {value1}.{value2}"), Map.of("value1", String.valueOf(moduleAlias), "value2", String.valueOf(uiSet.getSetType()))));
        }
    }

    private void rejectMutationWhenPublishedConfigExists(String uiSetId) {
        PlatformUiConfigService uiConfigService = uiConfigServiceProvider.get();
        if (uiConfigService != null && uiConfigService.hasPublishedConfigForUiSet(uiSetId)) {
            throw BusinessExceptions.warningText("platform.ui-set.published-config-mutation-denied", TextReference.code(
                    "platform.rejection.platform-ui-set.ui-set-with-published-ui-config-cannot-be-changed-unpublish-first",
                    "UI set with published UI config cannot be changed; unpublish first: {value1}"), Map.of("value1", String.valueOf(uiSetId)));
        }
    }
}
