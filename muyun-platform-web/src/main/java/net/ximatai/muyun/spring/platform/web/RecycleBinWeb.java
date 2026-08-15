package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.platform.web.ProjectedRecordValues;
import net.ximatai.muyun.spring.platform.web.RecordReadVisibility;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinActionOutcome;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinItem;
import net.ximatai.muyun.spring.platform.deletion.RestoreReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Optional;

/**
 * HTTP adapter for the optional operator-facing recycle-bin lifecycle.
 *
 * <p>Resource services opt in through {@link RecycleBinAbility}; the platform
 * facade retains ownership of lifecycle history validation and recovery-tree
 * execution. A controller only supplies its standard service and the shared
 * facade, so every opted-in module exposes the same action and response
 * contract.</p>
 */
public interface RecycleBinWeb<T extends EntityContract, S extends RecycleBinAbility<T>>
        extends ScopedWeb<S>, RecordLabelWeb<T> {
    /** Shared lifecycle facade supplied by the platform application context. */
    RecycleBinFacade recycleBinFacade();

    @PostMapping("/recycle-bin/query")
    @ActionEndpoint(PlatformAction.RECYCLE_BIN_QUERY)
    default WebPageResponse<RecycleBinItem<?>> recycleBin(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            Optional<? extends WebPageResponse<?>> projected = recycleBinProjectedQuery(request);
            if (projected.isPresent()) {
                return decorateProjected(projected.get());
            }
            WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
            Criteria criteria = recycleBinQueryCriteria(request);
            Sort[] sorts = recycleBinQuerySorts(request);
            PageRequest pageRequest = PageRequest.of(page.pageNum(), page.pageSize());
            if (service() instanceof DataScopeAbility<?>) {
                DataScopeAbility<T> dataScopeAbility = DataScopeAbility.cast(service());
                DataScopeCriteriaResult scope = dataScopeAbility.readScopeByPolicy(
                        StaticStandardMutationSupport.actionPolicy(this, PlatformAction.RECYCLE_BIN_QUERY), criteria);
                WebPageResponse<T> response = dataScopeAbility.withDataScopeTenant(scope,
                        () -> WebPageResponse.from(service().pageRecycleBin(
                                scope.criteria(), pageRequest, sorts)));
                return decorateProjected(projectStaticFallback(response));
            }
            WebPageResponse<T> response = WebPageResponse.from(
                    service().pageRecycleBin(criteria, pageRequest, sorts));
            return decorateProjected(projectStaticFallback(response));
        });
    }

    /**
     * Returns a retained record as a read-only detail snapshot. The normal CRUD view endpoint intentionally
     * excludes soft-deleted records, so this uses the recycle-bin read policy instead.
     */
    @GetMapping("/recycle-bin/view/{id}")
    @ActionEndpoint(PlatformAction.RECYCLE_BIN_QUERY)
    default T viewRecycleBinRecord(@PathVariable String id) {
        return webScope(() -> {
            Criteria criteria = Criteria.of().eq("id", id);
            List<T> records;
            if (service() instanceof DataScopeAbility<?>) {
                DataScopeAbility<T> dataScopeAbility = DataScopeAbility.cast(service());
                DataScopeCriteriaResult scope = dataScopeAbility.readScopeByPolicy(
                        StaticStandardMutationSupport.actionPolicy(this, PlatformAction.RECYCLE_BIN_QUERY), criteria);
                records = dataScopeAbility.withDataScopeTenant(scope,
                        () -> service().pageRecycleBin(scope.criteria(), PageRequest.of(1, 1)).getRecords());
            } else {
                records = service().pageRecycleBin(criteria, PageRequest.of(1, 1)).getRecords();
            }
            if (records.isEmpty()) {
                throw new IllegalArgumentException("recycle-bin record not found: " + id);
            }
            return WebOutputSupport.record(service(), records.getFirst(), FieldOutputContext.VIEW);
        });
    }

    @SuppressWarnings("unchecked")
    private Optional<? extends WebPageResponse<?>> recycleBinProjectedQuery(WebQueryRequest request) {
        if (!(this instanceof CrudWeb<?, ?> crudWeb)) {
            return Optional.empty();
        }
        CrudWeb<T, ?> typedCrudWeb = (CrudWeb<T, ?>) crudWeb;
        return typedCrudWeb.queryStaticProjectedList(request, RecordReadVisibility.RETAINED);
    }

    private WebPageResponse<RecycleBinItem<?>> decorateProjected(WebPageResponse<?> response) {
        List<? extends RecycleBinItem<?>> records = decorateRecords(response.records());
        return new WebPageResponse<>(
                List.copyOf(records),
                response.total(), response.pageNum(), response.pageSize(), response.pages(),
                response.totalKnown(), response.navigation());
    }

    @SuppressWarnings("unchecked")
    private WebPageResponse<?> projectStaticFallback(WebPageResponse<T> response) {
        if (!(this instanceof CrudWeb<?, ?> crudWeb)) {
            return response;
        }
        return ((CrudWeb<T, ?>) crudWeb).projectStaticDefaultList(response);
    }

    private <R> List<RecycleBinItem<R>> decorateRecords(List<R> records) {
        return recycleBinFacade().items(service(), records,
                ProjectedRecordValues::id, ProjectedRecordValues::deletedAt);
    }

    @SuppressWarnings("unchecked")
    private Criteria recycleBinQueryCriteria(WebQueryRequest request) {
        if (this instanceof CrudWeb<?, ?> crudWeb) {
            return ((CrudWeb<T, ?>) crudWeb).queryCriteria(request);
        }
        return Criteria.of();
    }

    @SuppressWarnings("unchecked")
    private Sort[] recycleBinQuerySorts(WebQueryRequest request) {
        if (this instanceof CrudWeb<?, ?> crudWeb) {
            return ((CrudWeb<T, ?>) crudWeb).querySorts(request);
        }
        return new Sort[0];
    }

    @PostMapping("/recycle-bin/{sourceDeleteOperationId}/restore")
    @ActionEndpoint(PlatformAction.RECYCLE_BIN_RESTORE)
    @BusinessMutation
    default RestoreReport restoreFromRecycleBin(@PathVariable String sourceDeleteOperationId) {
        return webScope(() -> {
            RecycleBinActionOutcome<T, RestoreReport> outcome =
                    recycleBinFacade().restoreWithSource(service(), sourceDeleteOperationId);
            RecycleBinMutationResultSupport.restored(webScopeName(), outcome.recordId(),
                    recordLabel(outcome.record()), outcome.report());
            return outcome.report();
        });
    }

}
