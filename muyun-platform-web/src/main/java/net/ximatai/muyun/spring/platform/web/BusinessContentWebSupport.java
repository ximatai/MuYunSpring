package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.platform.content.BusinessContentService;
import net.ximatai.muyun.spring.platform.content.BusinessContentService.Location;
import net.ximatai.muyun.spring.platform.content.BusinessContentTranslationService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;
import java.util.stream.Collectors;

/** Standard-record bridge: owning VIEW scopes and compiled field exposure remain authoritative. */
public final class BusinessContentWebSupport {
    static final String ATTRIBUTE = BusinessContentWebSupport.class.getName();
    private final BusinessContentService content;
    private final ModuleExecutionPlanCatalog plans;
    private final BusinessContentTranslationService translations;
    public BusinessContentWebSupport(BusinessContentService content, ModuleExecutionPlanCatalog plans, BusinessContentTranslationService translations) {
        this.content = Objects.requireNonNull(content); this.plans = Objects.requireNonNull(plans); this.translations = translations;
    }
    static BusinessContentWebSupport current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(ATTRIBUTE) instanceof BusinessContentWebSupport support) return support;
        throw new IllegalStateException("Business content Web support is unavailable");
    }
    public BusinessContentService.Sources sources(CrudWeb<?, ?> web, String id) {
        web.view(id); // Includes the owning controller's menu, page selection and aggregate VIEW rules.
        var allowed = fields(web);
        var sources = content.sources(web.service(), id);
        Map<String, BusinessContentService.CurrentSource> visible = new LinkedHashMap<>();
        sources.fields().forEach((field, source) -> { if (allowed.contains(field)) visible.put(field, source); });
        return new BusinessContentService.Sources(sources.recordVersion(), visible);
    }
    public List<BusinessContentService.SourceRevision> history(CrudWeb<?, ?> web, String id, String field, PageRequest page) {
        web.view(id);
        if (!fields(web).contains(field)) throw denied();
        return content.history(web.service(), id, field, page);
    }
    public BusinessContentService.Sources childSources(CrudWeb<?, ?> web, String parentId, String relation, String childId) {
        web.view(parentId);
        var allowed = childFields(web, relation);
        var source = content.childSources(web.service(), parentId, relation, childId);
        Map<String, BusinessContentService.CurrentSource> visible = new LinkedHashMap<>();
        source.fields().forEach((field, value) -> { if (allowed.contains(field)) visible.put(field, value); });
        return new BusinessContentService.Sources(source.recordVersion(), visible);
    }
    public List<BusinessContentService.SourceRevision> childHistory(CrudWeb<?, ?> web, String parentId, String relation,
                                                                    String childId, String field, PageRequest page) {
        web.view(parentId);
        if (!childFields(web, relation).contains(field)) throw denied();
        return content.childHistory(web.service(), parentId, relation, childId, field, page);
    }
    public BusinessContentTranslationService.Reading reading(CrudWeb<?, ?> web, Location location, String language) {
        web.view(location.recordId());
        var allowed = location.childId() == null ? fields(web) : childFields(web, location.relationCode());
        var reading = translationService().read(web.service(), location, language);
        Map<String, BusinessContentTranslationService.ReadingField> visible = new LinkedHashMap<>();
        reading.fields().forEach((field, value) -> { if (allowed.contains(field)) visible.put(field, value); });
        return new BusinessContentTranslationService.Reading(reading.recordVersion(), reading.language(), visible);
    }
    public BusinessContentTranslationService.Maintenance maintain(CrudWeb<?, ?> web, Location location, String field,
            String language, String operation, BusinessContentTranslationService.Change change) {
        PlatformAction action = "confirm".equals(operation) || "review".equals(operation)
                ? PlatformAction.CONTENT_CONFIRM : PlatformAction.CONTENT_EDIT;
        requireTranslationField(web, location, field, action);
        return switch (operation) {
            case "prepare", "review" -> translationService().prepare(web.service(), location, field, language, action);
            case "draft" -> translationService().saveDraft(web.service(), location, field, language, change);
            case "confirm" -> translationService().confirm(web.service(), location, field, language, change);
            case "remove" -> translationService().remove(web.service(), location, field, language, change);
            default -> throw new IllegalArgumentException("Unknown translation operation");
        };
    }
    public List<BusinessContentTranslationService.TranslationRevision> translationHistory(CrudWeb<?, ?> web, Location location,
            String field, String language, String originalId, int revision, PageRequest page, PlatformAction action) {
        requireTranslationField(web, location, field, action);
        return translationService().history(web.service(), location, field, language, originalId, revision, page, action);
    }
    private <T extends net.ximatai.muyun.spring.common.model.contract.EntityContract> void requireTranslationField(CrudWeb<T, ?> web, Location location, String field, PlatformAction action) {
        var record = web.view(location.recordId());
        web.requireMenuEntryAction(action);
        web.requireMenuEntryRecord(action, record);
        PageContextScopePolicy.requireRecordInScope(record, web.recordScopeBindings(),
                web.webScopeName(), action, web.pageSelectionContextResolvers());
        var allowed = location.childId() == null ? fields(web) : childFields(web, location.relationCode());
        if (!allowed.contains(field)) throw denied();
    }
    private BusinessContentTranslationService translationService() {
        if (translations == null) throw new IllegalStateException("Business content translation support is unavailable");
        return translations;
    }

    private Set<String> childFields(CrudWeb<?, ?> web, String relationCode) {
        var plan = plans.find(web.webScopeName()).orElseThrow(BusinessContentWebSupport::denied);
        var relation = plan.uiDescriptor().detailRelations().stream().filter(value -> relationCode.equals(value.code())
                        && web.webScopeName().equals(value.targetModuleAlias()))
                .findFirst().orElseThrow(BusinessContentWebSupport::denied);
        var projection = relation.listProjection() != null ? relation.listProjection()
                : relation.queryContract() == null ? null : relation.queryContract().listProjection();
        // Dynamic management read models describe the main row. Child display fields belong to
        // the resolved relation projection, not to an assumed flattened main read model.
        if (projection != null) return projection.fields().stream()
                .map(net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationListField::fieldName)
                .collect(Collectors.toUnmodifiableSet());
        return plan.readModel().fields().stream().filter(field -> relation.targetEntityAlias().equals(field.entityAlias()))
                .map(ResolvedModuleReadField::fieldName).collect(Collectors.toUnmodifiableSet());
    }
    private Set<String> fields(CrudWeb<?, ?> web) {
        var plan = plans.find(web.webScopeName()).orElseThrow(BusinessContentWebSupport::denied);
        return plan.readModel().fields().stream().filter(field -> field.relationCode() == null
                        && Objects.equals(field.entityAlias(), plan.readModel().mainEntityAlias()))
                .map(ResolvedModuleReadField::fieldName).collect(Collectors.toUnmodifiableSet());
    }
    private static PlatformAccessDeniedException denied() {
        return new PlatformAccessDeniedException("Business content source is unavailable");
    }
}
