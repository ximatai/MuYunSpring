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
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class PlatformQueryTemplateService extends AbstractAbilityService<PlatformQueryTemplate> implements
        SoftDeleteAbility<PlatformQueryTemplate>,
        EnableAbility<PlatformQueryTemplate>,
        SortAbility<PlatformQueryTemplate>,
        QueryAbility<PlatformQueryTemplate> {
    public static final String MODULE_ALIAS = "platform.query_template";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final PlatformModuleService moduleService;

    public PlatformQueryTemplateService(BaseDao<PlatformQueryTemplate, String> queryTemplateDao,
                                        PlatformModuleService moduleService) {
        super(MODULE_ALIAS, PlatformQueryTemplate.class, queryTemplateDao);
        this.moduleService = moduleService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformQueryTemplate.class, java.util.List.of("alias", "title", "defaultTemplate", "published", "enabled"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("alias"));
    }

    @Override
    public void beforeInsert(PlatformQueryTemplate template) {
        normalizeAndValidate(template);
        rejectDirectPublish(template);
    }

    @Override
    public void beforeUpdate(PlatformQueryTemplate template) {
        PlatformQueryTemplate existing = selectIncludingDeleted(template.getId());
        rejectDirectPublish(existing, template);
        rejectPublishedEdit(existing, template);
        normalizeAndValidate(template);
        rejectChangedText(existing, template, TextReference.code(
                "platform.rejection.platform-query-template.query-template-modulealias-cannot-be-changed",
                "Query template moduleAlias cannot be changed"), PlatformQueryTemplate::getModuleAlias);
        rejectChangedText(existing, template, TextReference.code(
                "platform.rejection.platform-query-template.query-template-alias-cannot-be-changed",
                "Query template alias cannot be changed"), PlatformQueryTemplate::getAlias);
    }

    @Override
    public void beforeDelete(String id) {
        PlatformQueryTemplate existing = select(id);
        if (existing != null && Boolean.TRUE.equals(existing.getPublished())) {
            throw BusinessExceptions.warningText("platform.query-template.published-delete-denied", TextReference.code(
                    "platform.rejection.platform-query-template.published-query-template-cannot-be-deleted-unpublish-first",
                    "Published query template cannot be deleted; unpublish first: {value1}"), Map.of("value1", String.valueOf(id)));
        }
    }

    public PlatformQueryTemplate requireQueryTemplate(String id) {
        PlatformQueryTemplate template = id == null || id.isBlank() ? null : select(id);
        if (template == null) {
            throw BusinessExceptions.warningText("platform.query-template.not-found", TextReference.code(
                    "platform.rejection.platform-query-template.query-template-requires-existing-config",
                    "Query template requires existing config: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        return template;
    }

    public List<PlatformQueryTemplate> listByModule(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        return list(enabledCriteria(Criteria.of().eq("moduleAlias", validAlias)),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public List<PlatformQueryTemplate> listPublishedByModule(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        return list(enabledCriteria(Criteria.of()
                        .eq("moduleAlias", validAlias)
                        .eq("published", Boolean.TRUE)),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    private void normalizeAndValidate(PlatformQueryTemplate template) {
        String moduleAlias = PlatformNameRules.requireModuleAlias(template.getModuleAlias());
        PlatformModule module = moduleService.resolveVisibleModule(moduleAlias);
        if (module == null) {
            throw BusinessExceptions.warningText("platform.query-template.module-not-found", TextReference.code(
                    "platform.rejection.platform-query-template.query-template-requires-existing-module",
                    "Query template requires existing module: {value1}"), Map.of("value1", String.valueOf(moduleAlias)));
        }
        String alias = PlatformNameRules.requireIdentifier(template.getAlias(), "queryTemplateAlias");
        template.setModuleAlias(moduleAlias);
        template.setAlias(alias);
        if (template.getTitle() == null || template.getTitle().isBlank()) {
            template.setTitle(alias);
        }
        if (template.getDefaultTemplate() == null) {
            template.setDefaultTemplate(Boolean.FALSE);
        }
        if (template.getPublished() == null) {
            template.setPublished(Boolean.FALSE);
        }
        rejectDuplicate(template, Criteria.of()
                        .eq("moduleAlias", moduleAlias)
                        .eq("alias", alias), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.platform-query-template.query-template-alias-must-be-unique-in-module",
                                "Query template alias must be unique in module: {value1}.{value2}"), Map.of("value1", String.valueOf(moduleAlias), "value2", String.valueOf(alias))));
        if (Boolean.TRUE.equals(template.getDefaultTemplate())) {
            rejectDuplicate(template, Criteria.of()
                            .eq("moduleAlias", moduleAlias)
                            .eq("defaultTemplate", Boolean.TRUE), () -> PlatformErrors.badRequestText(TextReference.code(
                                    "platform.rejection.platform-query-template.only-one-default-query-template-is-allowed-for-module",
                                    "Only one default query template is allowed for module: {value1}"), Map.of("value1", String.valueOf(moduleAlias))));
        }
    }

    private void rejectPublishedEdit(PlatformQueryTemplate existing, PlatformQueryTemplate updated) {
        if (existing == null || !Boolean.TRUE.equals(existing.getPublished())) {
            return;
        }
        if (Boolean.FALSE.equals(updated.getPublished())
                && Objects.equals(existing.getModuleAlias(), updated.getModuleAlias())
                && Objects.equals(existing.getAlias(), updated.getAlias())
                && Objects.equals(existing.getDefaultTemplate(), updated.getDefaultTemplate())
                && Objects.equals(existing.getTitle(), updated.getTitle())
                && Objects.equals(existing.getEnabled(), updated.getEnabled())
                && Objects.equals(existing.getSortOrder(), updated.getSortOrder())) {
            return;
        }
        throw BusinessExceptions.warningText("platform.query-template.published-edit-denied", TextReference.code(
                "platform.rejection.platform-query-template.published-query-template-cannot-be-edited-unpublish-first",
                "Published query template cannot be edited; unpublish first: {value1}"), Map.of("value1", String.valueOf(existing.getId())));
    }

    private void rejectDirectPublish(PlatformQueryTemplate template) {
        if (Boolean.TRUE.equals(template.getPublished()) && !PlatformPageConfigPublishContext.active()) {
            throw BusinessExceptions.warningText("platform.query-template.direct-publish-denied", TextReference.code(
                    "platform.rejection.platform-query-template.query-template-can-only-be-published-through-publish-service",
                    "Query template can only be published through publish service: {value1}"), Map.of("value1", String.valueOf(template.getId())));
        }
    }

    private void rejectDirectPublish(PlatformQueryTemplate existing, PlatformQueryTemplate updated) {
        if (PlatformPageConfigPublishContext.active() || updated == null || !Boolean.TRUE.equals(updated.getPublished())) {
            return;
        }
        if (existing == null || !Boolean.TRUE.equals(existing.getPublished())) {
            throw BusinessExceptions.warningText("platform.query-template.direct-publish-denied", TextReference.code(
                    "platform.rejection.platform-query-template.query-template-can-only-be-published-through-publish-service",
                    "Query template can only be published through publish service: {value1}"), Map.of("value1", String.valueOf(updated.getId())));
        }
    }
}
