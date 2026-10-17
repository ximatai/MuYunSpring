package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.platform.content.BusinessContentService;
import net.ximatai.muyun.spring.platform.content.BusinessContentService.Location;
import net.ximatai.muyun.spring.platform.content.BusinessContentTranslationService;
import net.ximatai.muyun.spring.platform.content.BusinessContentGenerationService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.content.ContentPolicy;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;

/** Standard-record bridge: owning VIEW scopes and compiled field exposure remain authoritative. */
public final class BusinessContentWebSupport {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BusinessContentWebSupport.class);
    static final String ATTRIBUTE = BusinessContentWebSupport.class.getName();
    private final BusinessContentService content;
    private final ModuleExecutionPlanCatalog plans;
    private final BusinessContentTranslationService translations;
    private final BusinessContentGenerationService generations;
    private final BusinessContentGenerationWebScope generationScopes;
    public BusinessContentWebSupport(BusinessContentService content, ModuleExecutionPlanCatalog plans, BusinessContentTranslationService translations) {
        this(content, plans, translations, null, null);
    }
    public BusinessContentWebSupport(BusinessContentService content, ModuleExecutionPlanCatalog plans,
            BusinessContentTranslationService translations, BusinessContentGenerationService generations,
            BusinessContentGenerationWebScope generationScopes) {
        this.content = Objects.requireNonNull(content); this.plans = Objects.requireNonNull(plans); this.translations = translations;
        this.generations = generations; this.generationScopes = generationScopes;
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
    public BusinessContentGenerationService.Status generation(CrudWeb<?, ?> web, Location location, String field,
            String language, boolean request, boolean retry) {
        if (generations == null || generationScopes == null) throw new IllegalStateException("Business content generation support is unavailable");
        var scope = generationScopes.capture(web, location, field);
        return request ? generations.request(scope, web.webScopeName(), location, field, language, retry)
                : generations.status(scope, web.webScopeName(), location, field, language);
    }

    /** Preflight the whole bounded selection before creating any work. Each item still reopens its live owning scope. */
    public BusinessContentGenerationBatch.Result generationBatch(CrudWeb<?, ?> web, String parentId, String relationCode,
            BusinessContentGenerationBatch.Command command, boolean request) {
        var planned = new ArrayList<BatchField>();
        var output = new ArrayList<BusinessContentGenerationBatch.Item>();
        var diagnostics = new HashSet<String>();
        int records = command.ids().size();
        for (String id : command.ids()) {
            var location = parentId == null ? Location.record(id) : new Location(parentId, relationCode, id);
            boolean readable = planBatchFields(web, location, planned, diagnostics);
            requireBatchSize(planned);
            if (readable && parentId == null && command.includeChildren()) {
                var plan = plans.find(web.webScopeName()).orElseThrow(BusinessContentWebSupport::denied);
                for (var relation : plan.uiDescriptor().detailRelations()) {
                    if (!web.webScopeName().equals(relation.targetModuleAlias())
                            || BusinessContentReadExposure.fields(plan, relation.code()).isEmpty()) continue;
                    BusinessContentService.ChildSelection selection;
                    try { selection = content.childSelection(web.service(), id, relation.code(), BusinessContentGenerationBatch.MAX_RECORDS - records); }
                    catch (RuntimeException unavailable) {
                        batchDiagnostic("relation", unavailable, diagnostics);
                        batchDiagnostic(planned, location, "UNAVAILABLE", "RELATION_UNAVAILABLE");
                        requireBatchSize(planned); continue;
                    }
                    if (selection.more()) throw BusinessContentGenerationBatch.tooLarge();
                    records += selection.ids().size();
                    for (String childId : selection.ids()) {
                        planBatchFields(web, new Location(id, relation.code(), childId), planned, diagnostics);
                        requireBatchSize(planned);
                    }
                }
            }
        }
        for (var item : planned) {
            BusinessContentGenerationService.Status result = item.terminal();
            if (result == null) {
                try { result = generation(web, item.location(), item.field(), command.language(), request, request && command.retry()); }
                catch (RuntimeException unavailable) {
                    batchDiagnostic("generation", unavailable, diagnostics);
                    result = batchState("UNAVAILABLE", "SCOPE_OR_GENERATION_UNAVAILABLE");
                }
            }
            output.add(batchItem(item.location(), item.field(), result));
        }
        return new BusinessContentGenerationBatch.Result(command.language(), output);
    }

    private static void requireBatchSize(List<BatchField> planned) {
        if (planned.stream().filter(item -> item.field() != null).count() > BusinessContentGenerationBatch.MAX_TEXTS)
            throw BusinessContentGenerationBatch.tooLarge();
    }
    private boolean planBatchFields(CrudWeb<?, ?> web, Location location, List<BatchField> planned, Set<String> diagnostics) {
        try {
            generationScopes.requireRead(web, location);
            var sources = location.childId() == null ? content.sources(web.service(), location.recordId())
                    : content.childSourcesForSelection(web.service(), location.recordId(), location.relationCode(), location.childId());
            var exposed = location.childId() == null ? fields(web) : childFields(web, location.relationCode());
            var fields = sources.fields().entrySet().stream()
                    .filter(entry -> exposed.contains(entry.getKey()))
                    .filter(entry -> entry.getValue().content().policy() == ContentPolicy.AUTO_TRANSLATE)
                    .map(Map.Entry::getKey).sorted().toList();
            if (fields.isEmpty()) batchDiagnostic(planned, location, "DISABLED", "NO_AUTOMATIC_FIELDS");
            else fields.forEach(field -> planned.add(new BatchField(location, field, null)));
            return true;
        } catch (RuntimeException unavailable) {
            batchDiagnostic("source", unavailable, diagnostics);
            batchDiagnostic(planned, location, "UNAVAILABLE", "SCOPE_OR_SOURCE_UNAVAILABLE");
            return false;
        }
    }
    private static void batchDiagnostic(List<BatchField> planned, Location location, String state, String problem) {
        var diagnostic = new BatchField(location, null, batchState(state, problem));
        for (int i = 0; i < planned.size(); i++) {
            if (planned.get(i).field() == null && planned.get(i).location().equals(location)) {
                planned.set(i, diagnostic); return;
            }
        }
        planned.add(diagnostic);
    }
    private static void batchDiagnostic(String stage, RuntimeException failure, Set<String> diagnostics) {
        if (failure instanceof PlatformAccessDeniedException
                || failure instanceof net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException) return;
        String type = failure.getClass().getSimpleName();
        if (diagnostics.add(stage + ":" + type)) LOG.warn("Business content selection {} unavailable ({})", stage, type);
    }
    private static BusinessContentGenerationBatch.Item batchItem(Location location, String field, BusinessContentGenerationService.Status status) {
        return new BusinessContentGenerationBatch.Item(location.childId() == null ? location.recordId() : location.childId(),
                location.recordId(), location.relationCode(), location.childId(), field, status);
    }
    private static BusinessContentGenerationService.Status batchState(String state, String problem) {
        return new BusinessContentGenerationService.Status(state, null, problem, 0, null);
    }
    private record BatchField(Location location, String field, BusinessContentGenerationService.Status terminal) { }
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
        return BusinessContentReadExposure.fields(plan, relationCode);
    }
    private Set<String> fields(CrudWeb<?, ?> web) {
        var plan = plans.find(web.webScopeName()).orElseThrow(BusinessContentWebSupport::denied);
        return BusinessContentReadExposure.fields(plan, null);
    }
    private static PlatformAccessDeniedException denied() {
        return new PlatformAccessDeniedException("Business content source is unavailable");
    }
}
