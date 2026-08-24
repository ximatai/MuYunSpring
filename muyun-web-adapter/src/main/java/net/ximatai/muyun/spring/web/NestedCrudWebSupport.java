package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.function.Supplier;

public abstract class NestedCrudWebSupport<T extends EntityContract, S extends CrudAbility<T>>
        extends WebSupport<S> implements SystemScope<S>, RecordLabelWeb<T> {

    /**
     * {@link WebSupport} also exposes {@link ScopedWeb}; make the intended system scope explicit
     * so Java's interface-default resolution cannot fall back to the tenant-required default.
     */
    @Override
    public <R> R webScope(Supplier<R> action) {
        return SystemScope.super.webScope(action);
    }

    protected Criteria queryCriteria(WebQueryRequest request) {
        if (service() instanceof QueryAbility<?> queryAbility) {
            Criteria criteria = queryAbility.queryCriteria(WebQueryRequests.from(request));
            return criteria == null ? Criteria.of() : criteria;
        }
        if (request != null && !request.conditions().isEmpty()) {
            throw new IllegalArgumentException("query conditions are not supported by " + webScopeName());
        }
        if (request != null && request.criteria() != null && !request.criteria().isEmpty()) {
            throw new IllegalArgumentException("query criteria are not supported by " + webScopeName());
        }
        return Criteria.of();
    }

    protected abstract void appendScope(Criteria criteria, HttpServletRequest request);

    protected abstract void bindScope(T record, HttpServletRequest request);

    protected abstract boolean inScope(T record, HttpServletRequest request);

    protected Sort[] querySorts(WebQueryRequest request) {
        if (service() instanceof QueryAbility<?> queryAbility) {
            Sort[] sorts = queryAbility.querySorts(WebQueryRequests.from(request));
            return sorts == null ? new Sort[0] : sorts;
        }
        if (request != null && !request.sorts().isEmpty()) {
            throw new IllegalArgumentException("query sorts are not supported by " + webScopeName());
        }
        return new Sort[0];
    }

    @PostMapping("/query")
    @ActionEndpoint(PlatformAction.QUERY)
    public WebPageResponse<T> query(HttpServletRequest servletRequest,
                                    @RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            Criteria criteria = queryCriteria(request);
            appendScope(criteria, servletRequest);
            WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
            PageResult<T> result = service().pageQuery(criteria,
                    PageRequest.of(page.pageNum(), page.pageSize()), querySorts(request));
            return WebPageResponse.from(WebOutputSupport.page(service(), result, FieldOutputContext.LIST));
        });
    }

    @GetMapping("/view/{id}")
    @ActionEndpoint(PlatformAction.VIEW)
    public T view(HttpServletRequest servletRequest, @PathVariable String id) {
        return webScope(() -> WebOutputSupport.record(service(), requireScopedRecord(servletRequest, id),
                FieldOutputContext.VIEW));
    }

    @PostMapping("/insert")
    @ActionEndpoint(PlatformAction.CREATE)
    @StandardMutation(StandardMutationKind.CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public T insert(HttpServletRequest servletRequest, @RequestBody T record) {
        return webScope(() -> {
            bindScope(record, servletRequest);
            String id = service().insert(record);
            T saved = WebOutputSupport.record(service(), service().select(id), FieldOutputContext.VIEW);
            StaticStandardMutationSupport.created(this, id);
            return saved;
        });
    }

    @PostMapping("/update/{id}")
    @ActionEndpoint(PlatformAction.UPDATE)
    @StandardMutation(StandardMutationKind.UPDATE)
    @Transactional
    public T update(HttpServletRequest servletRequest, @PathVariable String id,
                    @RequestBody T record) {
        return webScope(() -> {
            requireScopedRecord(servletRequest, id);
            record.setId(id);
            bindScope(record, servletRequest);
            service().update(record);
            T saved = WebOutputSupport.record(service(), service().select(id), FieldOutputContext.VIEW);
            StaticStandardMutationSupport.updated(this, id);
            return saved;
        });
    }

    @PostMapping("/delete/{id}")
    @ActionEndpoint(PlatformAction.DELETE)
    @StandardMutation(StandardMutationKind.DELETE)
    public int delete(HttpServletRequest servletRequest, @PathVariable String id,
                      @RequestBody RecordActionWebRequest request) {
        return webScope(() -> {
            requireScopedRecord(servletRequest, id);
            return StaticStandardMutationSupport.deleted(this, id, () -> service().delete(id, request.version()));
        });
    }

    protected T requireScopedRecord(HttpServletRequest request, String id) {
        T record = service().select(id);
        if (record == null || !inScope(record, request)) {
            throw new IllegalArgumentException(scopedRecordNotFoundMessage(request, id));
        }
        return record;
    }

    protected String pathVariable(HttpServletRequest request, String key) {
        Object value = pathVariables(request).get(key);
        return value == null ? null : value.toString();
    }

    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "nested record does not belong to request scope: " + id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> pathVariables(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables)) {
            return Map.of();
        }
        return variables.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toString(),
                        entry -> entry.getValue().toString()
                ));
    }
}
