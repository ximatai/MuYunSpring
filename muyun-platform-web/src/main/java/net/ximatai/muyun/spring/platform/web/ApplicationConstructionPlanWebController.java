package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.application.ApplicationConstructionPlanService;
import net.ximatai.muyun.spring.platform.application.ApplicationConstructionInitializationService;
import net.ximatai.muyun.spring.platform.application.ApplicationConstructionFieldService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import net.ximatai.muyun.spring.platform.application.ApplicationConstructionDeliveryService;

@RestController
@RequestMapping("/platform.application-construction-plans")
public class ApplicationConstructionPlanWebController {
    private final ApplicationConstructionPlanService plans;
    private final ApplicationConstructionInitializationService initialization;
    private final ApplicationConstructionFieldService fields;
    private final ApplicationConstructionDeliveryService delivery;
    public ApplicationConstructionPlanWebController(ApplicationConstructionPlanService plans, ApplicationConstructionInitializationService initialization, ApplicationConstructionFieldService fields, ApplicationConstructionDeliveryService delivery) {
        this.plans = plans; this.initialization = initialization; this.fields = fields; this.delivery = delivery;
    }
    @PostMapping("/{planId}/initializations/preview")
    public ApplicationConstructionInitializationService.Preview previewInitialization(@PathVariable String planId,
            @RequestBody ApplicationConstructionInitializationService.Proposal proposal) { return initialization.preview(planId, proposal); }
    @PostMapping("/{planId}/initializations")
    public ApplicationConstructionInitializationService.Result initialize(@PathVariable String planId,
            @RequestBody ApplicationConstructionInitializationService.ConfirmCommand command) { return initialization.confirm(planId, command); }
    @GetMapping("/{planId}/initializations/{objectKey}")
    public ResponseEntity<ApplicationConstructionInitializationService.Result> initialization(@PathVariable String planId, @PathVariable String objectKey) {
        var result = initialization.status(planId, objectKey);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }
    @GetMapping("/{planId}/objects/{objectKey}/fields")
    public ApplicationConstructionFieldService.Description fields(@PathVariable String planId, @PathVariable String objectKey) { return fields.describe(planId, objectKey); }
    @PostMapping("/{planId}/field-changes/preview")
    public ApplicationConstructionFieldService.Preview previewFields(@PathVariable String planId, @RequestBody ApplicationConstructionFieldService.Proposal proposal) { return fields.preview(planId, proposal); }
    @PostMapping("/{planId}/field-changes")
    public ApplicationConstructionFieldService.Result confirmFields(@PathVariable String planId, @RequestBody ApplicationConstructionFieldService.Command command) { return fields.confirm(planId, command); }
    @GetMapping("/{planId}/field-changes/{requestId}")
    public ResponseEntity<ApplicationConstructionFieldService.Result> fieldChange(@PathVariable String planId, @PathVariable String requestId) {
        var result = fields.status(planId, requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }
    @PostMapping("/{planId}/delivery/preview")
    public ApplicationConstructionDeliveryService.Preview previewDelivery(@PathVariable String planId, @RequestBody ApplicationConstructionDeliveryService.Proposal proposal) { return delivery.preview(planId, proposal); }
    @PostMapping("/{planId}/delivery")
    public ApplicationConstructionDeliveryService.Receipt confirmDelivery(@PathVariable String planId, @RequestBody ApplicationConstructionDeliveryService.Command command) { return delivery.confirm(planId, command); }
    @GetMapping("/{planId}/delivery/{requestId}")
    public ResponseEntity<ApplicationConstructionDeliveryService.Receipt> delivery(@PathVariable String planId, @PathVariable String requestId) {
        var result = delivery.result(planId, requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }
    @GetMapping("/{planId}/objects/{objectKey}/progress")
    public ApplicationConstructionDeliveryService.Progress progress(@PathVariable String planId, @PathVariable String objectKey) { return delivery.progress(planId, objectKey); }
    @GetMapping("/{planId}/objects/{objectKey}/acceptance-preview")
    public ApplicationConstructionDeliveryService.AcceptancePreview previewAcceptance(@PathVariable String planId, @PathVariable String objectKey) { return delivery.previewAcceptance(planId, objectKey); }
    @PostMapping("/{planId}/acceptances")
    public ApplicationConstructionDeliveryService.AcceptanceReceipt confirmAcceptance(@PathVariable String planId, @RequestBody ApplicationConstructionDeliveryService.AcceptanceCommand command) { return delivery.confirmAcceptance(planId, command); }
    @GetMapping("/{planId}/acceptances/{requestId}")
    public ResponseEntity<ApplicationConstructionDeliveryService.AcceptanceReceipt> acceptance(@PathVariable String planId, @PathVariable String requestId) {
        var result = delivery.acceptance(planId, requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }
    @GetMapping("/{planId}/task")
    public ApplicationConstructionDeliveryService.Task task(@PathVariable String planId) { return delivery.task(planId); }
    @GetMapping public List<ApplicationConstructionPlanService.Summary> list() { return plans.list(); }
    @GetMapping("/{planId}") public ApplicationConstructionPlanService.Snapshot read(@PathVariable String planId) { return plans.read(planId); }
    @GetMapping("/{planId}/revisions") public List<ApplicationConstructionPlanService.Snapshot> history(@PathVariable String planId) { return plans.history(planId); }
    @PostMapping("/{planId}/confirmations")
    public ApplicationConstructionPlanService.Snapshot confirm(@PathVariable String planId,
            @RequestBody ApplicationConstructionPlanService.ConfirmCommand command) { return plans.confirm(planId, command); }
    @GetMapping("/{planId}/confirmations/{requestId}")
    public ResponseEntity<ApplicationConstructionPlanService.Snapshot> confirmation(@PathVariable String planId, @PathVariable String requestId) {
        var result = plans.confirmation(planId, requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }
}
