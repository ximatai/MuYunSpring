package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.platform.content.BusinessContentService;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;
import java.util.stream.Collectors;

/** Standard-record bridge: owning VIEW scopes and compiled field exposure remain authoritative. */
public final class BusinessContentWebSupport {
    static final String ATTRIBUTE = BusinessContentWebSupport.class.getName();
    private final BusinessContentService content;
    private final ModuleExecutionPlanCatalog plans;
    public BusinessContentWebSupport(BusinessContentService content, ModuleExecutionPlanCatalog plans) {
        this.content = Objects.requireNonNull(content); this.plans = Objects.requireNonNull(plans);
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
