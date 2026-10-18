package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.content.*;
import net.ximatai.muyun.spring.platform.workflow.WorkflowOpinionContentService;
import net.ximatai.muyun.spring.web.BusinessMutation;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping({"/workflow/runtime/instance/{instanceId}/events/{eventId}/content",
        "/workflow/history/{instanceId}/events/{eventId}/content"})
public class WorkflowOpinionContentWebController {
    private final WorkflowOpinionContentService opinions;
    public WorkflowOpinionContentWebController(WorkflowOpinionContentService opinions) { this.opinions = opinions; }
    @GetMapping
    public BusinessContentService.Sources sources(@PathVariable String instanceId, @PathVariable String eventId) {
        return opinions.sources(instanceId, eventId);
    }
    @GetMapping("/reading")
    public BusinessContentTranslationService.Reading reading(@PathVariable String instanceId, @PathVariable String eventId,
                                                             @RequestParam String language) {
        return opinions.read(instanceId, eventId, language);
    }
    @GetMapping("/reason/history")
    public List<BusinessContentService.SourceRevision> history(@PathVariable String instanceId, @PathVariable String eventId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return opinions.history(instanceId, eventId, page(page, size));
    }
    @GetMapping("/reason/translations/{language}/generation")
    public BusinessContentGenerationService.Status generation(@PathVariable String instanceId, @PathVariable String eventId,
                                                               @PathVariable String language) {
        return opinions.generation(instanceId, eventId, language, false, false);
    }
    @PostMapping("/reason/translations/{language}/generation")
    public BusinessContentGenerationService.Status generate(@PathVariable String instanceId, @PathVariable String eventId,
            @PathVariable String language, @RequestParam(defaultValue = "false") boolean retry) {
        return opinions.generation(instanceId, eventId, language, true, retry);
    }
    @PostMapping("/reason/translations/{language}/prepare")
    @BusinessMutation(actionContextRequired = false)
    public BusinessContentTranslationService.Maintenance prepare(@PathVariable String instanceId, @PathVariable String eventId,
                                                                 @PathVariable String language) {
        return opinions.prepare(instanceId, eventId, language, PlatformAction.CONTENT_EDIT);
    }
    @PostMapping("/reason/translations/{language}/review")
    @BusinessMutation(actionContextRequired = false)
    public BusinessContentTranslationService.Maintenance review(@PathVariable String instanceId, @PathVariable String eventId,
                                                                @PathVariable String language) {
        return opinions.prepare(instanceId, eventId, language, PlatformAction.CONTENT_CONFIRM);
    }
    @PostMapping("/reason/translations/{language}/draft")
    @BusinessMutation(actionContextRequired = false)
    public BusinessContentTranslationService.Maintenance draft(@PathVariable String instanceId, @PathVariable String eventId,
            @PathVariable String language, @RequestBody BusinessContentTranslationService.Change change) {
        return opinions.draft(instanceId, eventId, language, change);
    }
    @PostMapping("/reason/translations/{language}/confirm")
    @BusinessMutation(actionContextRequired = false)
    public BusinessContentTranslationService.Maintenance confirm(@PathVariable String instanceId, @PathVariable String eventId,
            @PathVariable String language, @RequestBody BusinessContentTranslationService.Change change) {
        return opinions.confirm(instanceId, eventId, language, change);
    }
    @PostMapping("/reason/translations/{language}/remove")
    @BusinessMutation(actionContextRequired = false)
    public BusinessContentTranslationService.Maintenance remove(@PathVariable String instanceId, @PathVariable String eventId,
            @PathVariable String language, @RequestBody BusinessContentTranslationService.Change change) {
        return opinions.remove(instanceId, eventId, language, change);
    }
    @GetMapping("/reason/translations/{language}/history")
    public List<BusinessContentTranslationService.TranslationRevision> translationHistory(@PathVariable String instanceId,
            @PathVariable String eventId, @PathVariable String language, @RequestParam String sourceRevisionId,
            @RequestParam int sourceRevision, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return opinions.translations(instanceId, eventId, language, sourceRevisionId, sourceRevision, page(page, size), PlatformAction.CONTENT_EDIT);
    }
    @GetMapping("/reason/translations/{language}/review-history")
    public List<BusinessContentTranslationService.TranslationRevision> reviewedHistory(@PathVariable String instanceId,
            @PathVariable String eventId, @PathVariable String language, @RequestParam String sourceRevisionId,
            @RequestParam int sourceRevision, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return opinions.translations(instanceId, eventId, language, sourceRevisionId, sourceRevision, page(page, size), PlatformAction.CONTENT_CONFIRM);
    }
    private static PageRequest page(int page, int size) {
        if (page < 1 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid content history page");
        return PageRequest.of(page, size);
    }
}
