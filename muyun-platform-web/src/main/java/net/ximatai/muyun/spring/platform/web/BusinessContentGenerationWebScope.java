package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.platform.content.*;
import net.ximatai.muyun.spring.platform.menu.*;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Reopens server-owned compiled entry constraints without fabricating an HTTP request or restoring permission snapshots. */
@Component
public final class BusinessContentGenerationWebScope implements BusinessContentGenerationScope {
    private static final String KEY = "standard-record-web";
    private final ApplicationContext context;
    private final ModuleExecutionPlanCatalog plans;
    private final BusinessContentService content;
    private final ObjectProvider<StaticAbilityCatalog> statics;
    private final ObjectProvider<DynamicRecordService> dynamics;
    private final ObjectProvider<MenuService> menus;
    private final ObjectMapper json;
    private final ActionEndpointContextResolver actions;
    private final ObjectProvider<ModuleTenantScope> tenantScopes;

    public BusinessContentGenerationWebScope(ApplicationContext context, ModuleExecutionPlanCatalog plans,
            BusinessContentService content, ObjectProvider<StaticAbilityCatalog> statics,
            ObjectProvider<DynamicRecordService> dynamics, ObjectProvider<MenuService> menus, ObjectMapper json,
            ActionEndpointContextResolver actions, ObjectProvider<ModuleTenantScope> tenantScopes) {
        this.context = context; this.plans = plans; this.content = content;
        this.statics = statics; this.dynamics = dynamics; this.menus = menus; this.json = json;
        this.actions = Objects.requireNonNull(actions);
        this.tenantScopes = Objects.requireNonNull(tenantScopes);
    }
    @Override public String key() { return KEY; }

    /** The descriptor is constructed from the authenticated handler and live server facts, never from request JSON. */
    public Reference capture(CrudWeb<?, ?> web, BusinessContentService.Location location, String field) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) throw denied();
        web.view(location.recordId()); web.requireMenuEntryAction(PlatformAction.VIEW);
        String module = web.webScopeName();
        var plan = plans.find(module).orElseThrow(BusinessContentGenerationWebScope::denied);
        if (!BusinessContentReadExposure.fields(plan, location.relationCode()).contains(field)) throw denied();
        // Compiled plans are the source of truth. A custom request-only scope cannot silently
        // disappear in a worker; publish it into the standard plan before enabling generation.
        var bindings = PageContextScopePolicy.recordScopeBindings(plan.pageContextBindings());
        if (!bindings.equals(web.recordScopeBindings())) throw denied();
        var owner = content.requireOwner(web.service(), location);
        PageContextScopePolicy.requireRecordValues(bindings, module, PlatformAction.VIEW,
                web.pageSelectionContextResolvers(), name -> value(owner, name));
        Map<String, String> scopeHashes = new TreeMap<>();
        for (var binding : bindings) scopeHashes.put(binding.targetKey(), valueVersion(value(owner, binding.targetKey())));
        var selection = bindings.stream().anyMatch(binding -> binding.source() == PageContextSource.RESOLVED_SELECTION)
                ? PageSelectionContextRequestHeader.parse(attributes.getRequest().getHeader(PageSelectionContextRequestHeader.HEADER_NAME)) : null;
        if (selection != null && (selection.kind().length() > 128 || selection.key().length() > 2048)) throw denied();
        var entry = MenuEntryRequestContext.current().orElse(null);
        var menu = entry == null ? null : menu(entry.menuId());
        if (menu != null && (!Objects.equals(entry.moduleAlias(), menu.getModuleAlias())
                || !valueVersion(entry.entryParamsJson()).equals(valueVersion(menu.getEntryParamsJson())))) throw denied();
        var menuRef = menu == null ? null : new MenuReference(menu.getId(), menu.getCreatedAt(), menu.getModuleAlias(), valueVersion(menu.getEntryParamsJson()));
        if (menuRef != null && menuRef.createdAt() == null) throw denied();
        var policy = web.modulePageEntryPolicy();
        boolean ownedEntry = entry != null && policy != null && policy.supports(entry);
        String beanName = handlerBean(attributes);
        Object bean = context.getBean(beanName);
        if (!(bean instanceof CrudWeb<?, ?>) || !AopUtils.getTargetClass(bean).equals(AopUtils.getTargetClass(web))) throw denied();
        var descriptor = new Descriptor(module, location, field, beanName, AopUtils.getTargetClass(bean).getName(), plan.versionKey(),
                valueVersion(bindings), menuRef, ownedEntry, scopeHashes, selection);
        String payload = encode(descriptor);
        return new Reference(KEY, TextReference.versionOf(payload), payload);
    }

    @Override public <T> T withAccess(Reference reference, String module, BusinessContentService.Location location,
                                     String field, Function<CrudAbility<?>, T> work) {
        var previous = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try { return access(reference, module, location, field, work); }
        finally { if (previous != null) RequestContextHolder.setRequestAttributes(previous); }
    }

    private <T> T access(Reference reference, String module, BusinessContentService.Location location,
                         String field, Function<CrudAbility<?>, T> work) {
        if (!KEY.equals(reference.adapter()) || !TextReference.versionOf(reference.payload()).equals(reference.version())) throw denied();
        Descriptor saved;
        try { saved = json.readValue(reference.payload(), Descriptor.class); }
        catch (Exception invalid) { throw denied(); }
        if (saved == null || !module.equals(saved.moduleAlias()) || !location.equals(saved.location()) || !field.equals(saved.field())) throw denied();
        Object bean = context.getBean(saved.beanName());
        if (!(bean instanceof CrudWeb<?, ?> web) || !AopUtils.getTargetClass(bean).getName().equals(saved.beanType())) throw denied();
        var plan = plans.find(module).orElseThrow(BusinessContentGenerationWebScope::denied);
        var bindings = PageContextScopePolicy.recordScopeBindings(plan.pageContextBindings());
        if (!plan.versionKey().equals(saved.planVersion()) || !valueVersion(bindings).equals(saved.bindingsVersion())
                || !BusinessContentReadExposure.fields(plan, location.relationCode()).contains(field)) throw denied();
        // Dynamic plans do not duplicate the live module action registry. Reuse the same
        // published-action and governance resolution as ordinary static/dynamic HTTP entry points.
        var execution = actions.resolveActionCode(module, PlatformAction.VIEW.code(), Set.of(location.recordId()));
        CrudAbility<?> parent = ability(module);
        try (var action = ActionExecutionContextHolder.use(execution)) {
            var tenantScope = tenantScopes.getIfAvailable();
            if (tenantScope != null) tenantScope.requireActiveTenantIfRequired(module);
            var owner = content.requireOwner(parent, location);
            var entry = entry(saved.menu());
            requireMenuPolicy(web, entry, owner, saved.ownedEntry());
            ResolvedPageSelectionContext selection = null;
            if (bindings.stream().anyMatch(binding -> binding.source() == PageContextSource.RESOLVED_SELECTION)) {
                if (saved.selection() == null) throw denied();
                selection = web.pageSelectionContextResolvers().resolve(new PageSelectionContextRequest(module,
                        saved.selection().kind(), saved.selection().key(), PlatformAction.VIEW,
                        CurrentUserContext.currentUser().orElseThrow(BusinessContentGenerationWebScope::denied),
                        entry == null ? null : entry.menuId()));
            }
            for (var binding : bindings) {
                // Keep the original workspace as a constraint even if a live selection
                // could now resolve a different workspace the reader is also allowed to use.
                if (!Objects.equals(saved.scopeHashes().get(binding.targetKey()), valueVersion(value(owner, binding.targetKey())))) throw denied();
                if (binding.source() == PageContextSource.RESOLVED_SELECTION) {
                    if (selection == null || !binding.sourceKey().equals(saved.selection().kind())) throw denied();
                    var expected = selection.values().get(binding.targetKey());
                    if (expected == null || !expected.present() || !Objects.equals(expected.value(), value(owner, binding.targetKey()))) throw denied();
                } else if (binding.source() != PageContextSource.NAVIGATOR) throw denied();
            }
            return work.apply(parent);
        }
    }

    private CrudAbility<?> ability(String module) {
        var catalog = statics.getIfAvailable();
        var ability = catalog == null ? null : catalog.findByTarget(ReferenceTargets.fromModuleAlias(module)).orElse(null);
        if (ability == null) {
            var records = dynamics.getIfAvailable();
            if (records != null) ability = records.mainEntity(module);
        }
        if (ability == null || !module.equals(ability.getModuleAlias())) throw denied();
        return ability;
    }
    private String handlerBean(ServletRequestAttributes attributes) {
        Object current = attributes.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (!(current instanceof HandlerMethod handler)) throw denied();
        if (handler.getBean() instanceof String name) return name;
        var candidates = Arrays.stream(context.getBeanNamesForType(handler.getBeanType()))
                .filter(name -> context.getBean(name) == handler.getBean()).toList();
        if (candidates.size() != 1) throw denied();
        return candidates.getFirst();
    }
    private Menu menu(String id) {
        var service = menus.getIfAvailable(); var menu = service == null ? null : service.currentUserVisibleMenu(id);
        if (menu == null) throw denied(); return menu;
    }
    private MenuEntryRequestContext entry(MenuReference reference) {
        if (reference == null) return null;
        var menu = menu(reference.id());
        if (!Objects.equals(reference.createdAt(), menu.getCreatedAt()) || !Objects.equals(reference.moduleAlias(), menu.getModuleAlias())
                || !reference.paramsVersion().equals(valueVersion(menu.getEntryParamsJson()))) throw denied();
        return new MenuEntryRequestContext(menu.getId(), menu.getModuleAlias(), menu.getEntryParamsJson());
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void requireMenuPolicy(CrudWeb web, MenuEntryRequestContext entry, EntityContract owner, boolean wasOwned) {
        var policy = web.modulePageEntryPolicy();
        boolean owned = entry != null && policy != null && policy.supports(entry);
        if (wasOwned && !owned) throw denied();
        if (owned) { policy.requireAction(entry, PlatformAction.VIEW); policy.requireRecord(entry, PlatformAction.VIEW, owner); }
    }
    private static Object value(EntityContract owner, String field) {
        var properties = new BeanWrapperImpl(owner);
        if (properties.isReadableProperty(field)) return properties.getPropertyValue(field);
        if (owner instanceof DynamicRecord dynamic) return dynamic.getValue(field);
        throw denied();
    }
    private String valueVersion(Object value) { return TextReference.versionOf(encode(value)); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Cannot encode business content entry scope"); }
    }
    private static PlatformAccessDeniedException denied() { return new PlatformAccessDeniedException("Business content generation scope is unavailable"); }
    record MenuReference(String id, Instant createdAt, String moduleAlias, String paramsVersion) { }
    record Descriptor(String moduleAlias, BusinessContentService.Location location, String field,
                      String beanName, String beanType, String planVersion, String bindingsVersion, MenuReference menu,
                      boolean ownedEntry, Map<String, String> scopeHashes, PageSelectionContextRequestHeader.SelectionReference selection) { }
}
