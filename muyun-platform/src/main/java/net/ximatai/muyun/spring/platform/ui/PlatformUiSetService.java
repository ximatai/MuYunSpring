package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.List;
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

    public PlatformUiSetService(BaseDao<PlatformUiSet, String> uiSetDao,
                                PlatformModuleService moduleService) {
        super(MODULE_ALIAS, PlatformUiSet.class, uiSetDao);
        this.moduleService = moduleService;
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
        normalizeAndValidate(uiSet);
        PlatformUiSet existing = selectIncludingDeleted(uiSet.getId());
        rejectChanged(existing, uiSet, "UI set moduleAlias", PlatformUiSet::getModuleAlias);
        rejectChanged(existing, uiSet, "UI set alias", PlatformUiSet::getAlias);
    }

    public PlatformUiSet requireUiSet(String id) {
        PlatformUiSet uiSet = id == null || id.isBlank() ? null : select(id);
        if (uiSet == null) {
            throw BusinessExceptions.warning("platform.ui-set.not-found",
                    "UI set requires existing config: " + id);
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
            throw BusinessExceptions.warning("platform.ui-set.module-not-found",
                    "UI set requires existing module: " + moduleAlias);
        }
        String alias = PlatformNameRules.requireIdentifier(uiSet.getAlias(), "uiSetAlias");
        uiSet.setModuleAlias(moduleAlias);
        uiSet.setAlias(alias);
        if (uiSet.getSetType() == null) {
            throw BusinessExceptions.warning("platform.ui-set.type-required", "UI set type must not be null");
        }
        if (uiSet.getTitle() == null || uiSet.getTitle().isBlank()) {
            uiSet.setTitle(alias);
        }
        if (uiSet.getDefaultSet() == null) {
            uiSet.setDefaultSet(Boolean.FALSE);
        }
        rejectDuplicate(uiSet, Criteria.of()
                        .eq("moduleAlias", moduleAlias)
                        .eq("alias", alias),
                "UI set alias must be unique in module: " + moduleAlias + "." + alias);
        if (Boolean.TRUE.equals(uiSet.getDefaultSet())) {
            rejectDuplicate(uiSet, Criteria.of()
                            .eq("moduleAlias", moduleAlias)
                            .eq("setType", uiSet.getSetType())
                            .eq("defaultSet", Boolean.TRUE),
                    "Only one default UI set is allowed for module and type: "
                            + moduleAlias + "." + uiSet.getSetType());
        }
    }
}
