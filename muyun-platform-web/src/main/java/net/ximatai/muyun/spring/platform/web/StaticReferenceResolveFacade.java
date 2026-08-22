package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.title.TitleFieldResolver;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebReferenceMatchMode;
import net.ximatai.muyun.spring.web.WebReferenceResolveItem;
import net.ximatai.muyun.spring.web.WebReferenceResolveMode;
import net.ximatai.muyun.spring.web.WebReferenceResolveRequest;
import net.ximatai.muyun.spring.web.WebReferenceResolveResponse;
import net.ximatai.muyun.spring.web.WebReferenceResolveResult;
import net.ximatai.muyun.spring.web.WebReferenceResolveStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/** Default candidate/translation delivery for a reference declared on a static model. */
@Service
public class StaticReferenceResolveFacade {
    private final StaticModuleDefinitionCatalog modules;
    private final StaticAbilityCatalog abilities;

    public StaticReferenceResolveFacade(StaticModuleDefinitionCatalog modules, StaticAbilityCatalog abilities) {
        this.modules = modules;
        this.abilities = abilities;
    }

    public WebReferenceResolveResponse resolve(String moduleAlias, String fieldName,
                                               WebReferenceResolveRequest request) {
        CrudAbility<?> source = modules.find(moduleAlias)
                .flatMap(definition -> abilities.findByModel(definition.modelClass()))
                .orElseThrow(() -> new PlatformException("static module is not available: " + moduleAlias));
        ReferenceAbility<?> target = StaticReferenceResolver.rules(source.modelClass()).stream()
                .filter(rule -> rule.plan().sourceField().equals(fieldName))
                .findFirst()
                .flatMap(rule -> abilities.findReference(rule.target()))
                .orElseThrow(() -> new PlatformException("static reference field is not available: " + moduleAlias + "." + fieldName));
        WebReferenceResolveRequest normalized = request == null ? WebReferenceResolveRequest.empty() : request;
        return normalized.mode() == WebReferenceResolveMode.TRANSLATE
                ? translate(target, normalized)
                : query(target, normalized);
    }

    private WebReferenceResolveResponse query(ReferenceAbility<?> target, WebReferenceResolveRequest request) {
        WebPageRequest page = request.page() == null ? WebPageRequest.DEFAULT : request.page();
        Criteria criteria = Criteria.of();
        if (request.fuzzy() != null && !request.fuzzy().isBlank()) {
            criteria.like(TitleFieldResolver.resolveFieldName(target.modelClass()).orElse("title"), request.fuzzy().trim());
        }
        PageRequest pageRequest = PageRequest.of(page.pageNum(), page.pageSize());
        PageResult<ReferenceOption> result = target.referenceOptions(criteria, pageRequest);
        List<WebReferenceResolveItem> options = result.getRecords().stream()
                .map(option -> new WebReferenceResolveItem(option.id(), option.title(), null, null, null)).toList();
        return new WebReferenceResolveResponse(options.isEmpty() ? WebReferenceResolveStatus.NOT_FOUND : WebReferenceResolveStatus.OK,
                WebReferenceResolveMode.QUERY, options, List.of(), pageRequest.getOffset(), page.pageSize(), result.getTotal());
    }

    private WebReferenceResolveResponse translate(ReferenceAbility<?> target, WebReferenceResolveRequest request) {
        List<WebReferenceResolveResult> results = request.values().stream().map(value -> {
            String id = value == null ? null : String.valueOf(value);
            String title = id == null ? null : target.title(id);
            WebReferenceResolveItem item = title == null ? null
                    : new WebReferenceResolveItem(id, title, WebReferenceMatchMode.KEY, null, null);
            return new WebReferenceResolveResult(value,
                    item == null ? WebReferenceResolveStatus.NOT_FOUND : WebReferenceResolveStatus.RESOLVED,
                    item == null ? null : WebReferenceMatchMode.KEY, item, List.of());
        }).toList();
        WebReferenceResolveStatus status = results.isEmpty() ? WebReferenceResolveStatus.NOT_FOUND
                : results.stream().allMatch(result -> result.status() == WebReferenceResolveStatus.RESOLVED)
                ? WebReferenceResolveStatus.RESOLVED : WebReferenceResolveStatus.PARTIAL;
        return new WebReferenceResolveResponse(status, WebReferenceResolveMode.TRANSLATE, List.of(), results,
                0, 0, results.size());
    }
}
