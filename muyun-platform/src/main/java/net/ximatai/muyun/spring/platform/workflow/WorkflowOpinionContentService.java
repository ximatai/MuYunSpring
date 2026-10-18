package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.content.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Workflow opinions consume the same source, reading, maintenance and durable-generation contracts as business fields. */
@Service
public class WorkflowOpinionContentService {
    private final WorkflowOpinionAccess access;
    private final BusinessContentFacts facts;
    private final BusinessContentTranslationService translations;
    private final BusinessContentGenerationService generations;
    private final WorkflowOpinionContentScope scopes;
    public WorkflowOpinionContentService(WorkflowOpinionAccess access, BusinessContentFacts facts,
            BusinessContentTranslationService translations, BusinessContentGenerationService generations,
            WorkflowOpinionContentScope scopes) {
        this.access = java.util.Objects.requireNonNull(access, "access"); this.facts = java.util.Objects.requireNonNull(facts, "facts"); this.translations = java.util.Objects.requireNonNull(translations, "translations");
        this.generations = java.util.Objects.requireNonNull(generations, "generations"); this.scopes = java.util.Objects.requireNonNull(scopes, "scopes");
    }
    private BusinessContentFieldAccess field(String instanceId, String eventId) {
        return action -> access.require(instanceId, eventId, action).target();
    }
    public BusinessContentService.Sources sources(String instanceId, String eventId) {
        return facts.sources(access.require(instanceId, eventId, PlatformAction.VIEW).target());
    }
    public BusinessContentTranslationService.Reading read(String instanceId, String eventId, String language) {
        return translations.read(access.require(instanceId, eventId, PlatformAction.VIEW).target(), language);
    }
    public List<BusinessContentService.SourceRevision> history(String instanceId, String eventId, PageRequest page) {
        return facts.history(access.require(instanceId, eventId, PlatformAction.VIEW).target(), page);
    }
    public BusinessContentGenerationService.Status generation(String instanceId, String eventId, String language,
            boolean request, boolean retry) {
        var entry = access.require(instanceId, eventId, PlatformAction.VIEW);
        var reference = scopes.capture(entry.instance(), entry.event());
        var location = WorkflowOpinionContentScope.location(entry.instance(), entry.event());
        return request ? generations.request(reference, entry.instance().getModuleAlias(), location, "reason", language, retry)
                : generations.status(reference, entry.instance().getModuleAlias(), location, "reason", language);
    }
    @Transactional
    public BusinessContentTranslationService.Maintenance prepare(String instanceId, String eventId, String language,
                                                                 PlatformAction action) {
        if (action != PlatformAction.CONTENT_EDIT && action != PlatformAction.CONTENT_CONFIRM)
            throw new IllegalArgumentException("Opinion translation maintenance requires its own action");
        var entry = access.require(instanceId, eventId, action);
        // Legacy events know their real operator and occurrence time even when their language was not recorded.
        if (facts.sources(entry.target()).fields().get("reason").source() == null)
            facts.record(entry.target(), entry.event().getReasonSourceLanguage(), entry.event().getOperatorId(), null,
                    entry.event().getOccurredAt());
        return translations.prepare(field(instanceId, eventId), language, action);
    }
    public BusinessContentTranslationService.Maintenance draft(String instanceId, String eventId, String language,
                                                                BusinessContentTranslationService.Change change) {
        return translations.saveDraft(field(instanceId, eventId), language, change);
    }
    public BusinessContentTranslationService.Maintenance confirm(String instanceId, String eventId, String language,
                                                                  BusinessContentTranslationService.Change change) {
        return translations.confirm(field(instanceId, eventId), language, change);
    }
    public BusinessContentTranslationService.Maintenance remove(String instanceId, String eventId, String language,
                                                                 BusinessContentTranslationService.Change change) {
        return translations.remove(field(instanceId, eventId), language, change);
    }
    public List<BusinessContentTranslationService.TranslationRevision> translations(String instanceId, String eventId,
            String language, String originalId, int originalRevision, PageRequest page, PlatformAction action) {
        return translations.history(field(instanceId, eventId), language, originalId, originalRevision, page, action);
    }
}
