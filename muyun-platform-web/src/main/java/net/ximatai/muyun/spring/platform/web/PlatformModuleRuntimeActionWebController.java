package net.ximatai.muyun.spring.platform.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/{moduleAlias:[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+}")
public class PlatformModuleRuntimeActionWebController {
    private final PlatformRecordActionAvailabilityService recordActionAvailabilityService;

    public PlatformModuleRuntimeActionWebController(
            PlatformRecordActionAvailabilityService recordActionAvailabilityService) {
        this.recordActionAvailabilityService = recordActionAvailabilityService;
    }

    @GetMapping("/actions/{recordId}")
    public PlatformRecordActionAvailability recordActions(@PathVariable String moduleAlias,
                                                          @PathVariable String recordId) {
        return recordActionAvailabilityService.recordActions(moduleAlias, recordId);
    }

    @PostMapping("/actions/availability")
    public java.util.List<PlatformRecordActionAvailability> recordActions(@PathVariable String moduleAlias,
                                                                           @RequestBody RecordActionAvailabilityRequest request) {
        return recordActionAvailabilityService.recordActions(moduleAlias, request == null ? null : request.recordIds());
    }

    public record RecordActionAvailabilityRequest(java.util.List<String> recordIds) {
    }
}
