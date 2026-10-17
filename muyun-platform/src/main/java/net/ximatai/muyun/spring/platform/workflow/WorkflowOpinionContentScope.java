package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.tenant.TenantContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.content.BusinessContentGenerationScope;
import net.ximatai.muyun.spring.platform.content.BusinessContentService;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

/** Trusted workflow coordinates are reopened under the worker's restored actual actor and tenant. */
@Component
public final class WorkflowOpinionContentScope implements BusinessContentGenerationScope {
    private static final String KEY = "workflow-opinion";
    private final WorkflowOpinionAccess access;
    private final ObjectMapper json;
    public WorkflowOpinionContentScope(WorkflowOpinionAccess access, ObjectMapper json) { this.access = java.util.Objects.requireNonNull(access, "access"); this.json = java.util.Objects.requireNonNull(json, "json"); }
    @Override public String key() { return KEY; }
    public Reference capture(WorkflowInstance instance, WorkflowEvent event) {
        if (instance.getCreatedAt() == null || !Objects.equals(instance.getId(), event.getInstanceId())
                || !Objects.equals(instance.getTenantId(), event.getTenantId())) throw denied();
        var descriptor = new Descriptor(instance.getId(), instance.getCreatedAt(), instance.getModuleAlias(),
                instance.getRecordId(), event.getId());
        try {
            String payload = json.writeValueAsString(descriptor);
            return new Reference(KEY, TextReference.versionOf(payload), payload);
        } catch (Exception invalid) { throw denied(); }
    }
    public static BusinessContentService.Location location(WorkflowInstance instance, WorkflowEvent event) {
        return new BusinessContentService.Location(instance.getRecordId(), "workflow-events", event.getId());
    }
    @Override public <T> T withAccess(Reference reference, String moduleAlias, BusinessContentService.Location location,
                                     String field, Function<BusinessContentService.FieldTarget, T> work) {
        if (!KEY.equals(reference.adapter()) || !TextReference.versionOf(reference.payload()).equals(reference.version())
                || !"reason".equals(field) || !"workflow-events".equals(location.relationCode())) throw denied();
        Descriptor descriptor;
        try { descriptor = json.readValue(reference.payload(), Descriptor.class); }
        catch (Exception invalid) { throw denied(); }
        if (descriptor == null || !Objects.equals(moduleAlias, descriptor.moduleAlias())
                || !Objects.equals(location.recordId(), descriptor.recordId()) || !Objects.equals(location.childId(), descriptor.eventId())) throw denied();
        var entry = access.require(descriptor.instanceId(), descriptor.eventId(), PlatformAction.VIEW);
        if (!Objects.equals(TenantContext.currentTenantId().orElse(null), entry.instance().getTenantId())
                || !Objects.equals(descriptor.createdAt(), entry.instance().getCreatedAt())
                || !Objects.equals(descriptor.moduleAlias(), entry.instance().getModuleAlias())
                || !Objects.equals(descriptor.recordId(), entry.instance().getRecordId())) throw denied();
        return work.apply(entry.target());
    }
    private record Descriptor(String instanceId, Instant createdAt, String moduleAlias, String recordId, String eventId) { }
    private static PlatformAccessDeniedException denied() { return new PlatformAccessDeniedException("Workflow opinion scope is unavailable"); }
}
