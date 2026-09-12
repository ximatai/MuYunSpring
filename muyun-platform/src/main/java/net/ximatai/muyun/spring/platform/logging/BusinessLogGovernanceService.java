package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.ActionLogStatistics;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogCursor;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageResult;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadPage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScope;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsReader;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogStatistics;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogDetails;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Application boundary for administrative business-log reads.
 *
 * <p>This service deliberately accepts an already resolved {@link BusinessLogReadScope} rather
 * than depending on IAM or HTTP. Every entry point constrains caller filters again, so a Web
 * endpoint cannot widen the administrator's range by constructing an unrestricted query.</p>
 */
public final class BusinessLogGovernanceService {
    private static final Set<BusinessLogEventType> BUSINESS_ACTIVITY_TYPES = Set.of(
            BusinessLogEventType.ACTION, BusinessLogEventType.PAGE_ACCESS);
    private static final Set<BusinessLogEventType> REQUEST_ERROR_TYPES = Set.of(
            BusinessLogEventType.REQUEST_ERROR);
    private static final Set<BusinessLogEventType> LOGIN_AUDIT_TYPES = Set.of(
            BusinessLogEventType.LOGIN);

    private final BusinessLogStore store;
    private final BusinessLogStatisticsReader statisticsReader;

    public BusinessLogGovernanceService(BusinessLogStore store, BusinessLogStatisticsReader statisticsReader) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.statisticsReader = Objects.requireNonNull(statisticsReader, "statisticsReader must not be null");
    }

    /** Reads action and page-access facts only, within the immutable administrative range. */
    public BusinessLogReadPage queryBusinessActivities(BusinessLogQuery query, BusinessLogReadScope scope) {
        return store.read(constrain(query, scope, BUSINESS_ACTIVITY_TYPES));
    }

    /** Reads login-audit facts only, within the immutable administrative range. */
    public BusinessLogReadPage queryLoginAudits(BusinessLogQuery query, BusinessLogReadScope scope) {
        return store.read(constrain(query, scope, LOGIN_AUDIT_TYPES));
    }

    /** Reads request-error facts only, within the immutable administrative range. */
    public BusinessLogReadPage queryRequestErrors(BusinessLogQuery query, BusinessLogReadScope scope) {
        return store.read(constrain(query, scope, REQUEST_ERROR_TYPES));
    }

    /** Adapts a standard page request to cursor-backed action and page-access storage. */
    public BusinessLogPageResult queryBusinessActivitiesPage(BusinessLogQuery query, BusinessLogReadScope scope,
                                                              BusinessLogPageRequest page) {
        return readStandardPage(query, page, next -> queryBusinessActivities(next, scope));
    }

    /** Adapts a standard page request to cursor-backed login-audit storage. */
    public BusinessLogPageResult queryLoginAuditsPage(BusinessLogQuery query, BusinessLogReadScope scope,
                                                       BusinessLogPageRequest page) {
        return readStandardPage(query, page, next -> queryLoginAudits(next, scope));
    }

    /** Adapts a standard page request to cursor-backed request-error storage. */
    public BusinessLogPageResult queryRequestErrorsPage(BusinessLogQuery query, BusinessLogReadScope scope,
                                                         BusinessLogPageRequest page) {
        return readStandardPage(query, page, next -> queryRequestErrors(next, scope));
    }

    /** Returns an action or page-access detail only when it remains visible to the supplied scope. */
    public Optional<BusinessLogEvent> findBusinessActivityDetail(String eventId, BusinessLogReadScope scope) {
        return findVisibleDetail(eventId, scope, BUSINESS_ACTIVITY_TYPES);
    }

    /** Returns a login-audit fact only when it remains visible to the supplied scope. */
    public Optional<BusinessLogEvent> findLoginAuditDetail(String eventId, BusinessLogReadScope scope) {
        return findVisibleDetail(eventId, scope, LOGIN_AUDIT_TYPES);
    }

    /** Returns a request-error fact only when it remains visible to the supplied scope. */
    public Optional<BusinessLogEvent> findRequestErrorDetail(String eventId, BusinessLogReadScope scope) {
        return findVisibleDetail(eventId, scope, REQUEST_ERROR_TYPES);
    }

    /** Aggregates action facts over the immutable administrative range. */
    public ActionLogStatistics actionStatistics(BusinessLogStatisticsQuery query, BusinessLogReadScope scope) {
        return statisticsReader.actionStatistics(constrain(query, scope));
    }

    /** Aggregates page-access facts over the immutable administrative range. */
    public PageAccessLogStatistics pageAccessStatistics(BusinessLogStatisticsQuery query, BusinessLogReadScope scope) {
        return statisticsReader.pageAccessStatistics(constrain(query, scope));
    }

    /**
     * Returns the internal request-error diagnostic only to platform-wide administrators.
     * Web adapters must use this separate gate instead of serializing an error fact's details.
     */
    public Optional<RequestErrorLogDetails> findRequestErrorDiagnostic(String eventId, BusinessLogReadScope scope) {
        BusinessLogReadScope resolvedScope = requireScope(scope);
        if (!resolvedScope.isPlatformScope()) {
            throw new SecurityException("Request-error diagnostics require a platform read scope");
        }
        return store.findById(requireEventId(eventId))
                .filter(RequestErrorLogEvent.class::isInstance)
                .map(RequestErrorLogEvent.class::cast)
                .map(RequestErrorLogEvent::details);
    }

    /**
     * Cursor stores cannot seek by offset. This bounded adapter advances through their stable
     * descending cursor internally, leaving HTTP clients on the standard page/pageSize contract.
     */
    private static BusinessLogPageResult readStandardPage(BusinessLogQuery query,
                                                           BusinessLogPageRequest page,
                                                           Function<BusinessLogQuery, BusinessLogReadPage> reader) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(page, "page must not be null");
        Objects.requireNonNull(reader, "reader must not be null");
        long remainingSkip = page.offset();
        long consumedBeforePage = 0;
        int remainingPageSize = page.pageSize();
        BusinessLogCursor cursor = query.cursor();
        List<BusinessLogEvent> events = new ArrayList<>(page.pageSize());
        BusinessLogCursor nextCursor = null;

        while (remainingSkip > 0) {
            int chunkSize = (int) Math.min(200, remainingSkip);
            BusinessLogReadPage chunk = reader.apply(withCursor(query, cursor, chunkSize));
            int read = chunk.events().size();
            consumedBeforePage += read;
            remainingSkip -= read;
            if (remainingSkip == 0) {
                if (chunk.nextCursor() == null) {
                    return new BusinessLogPageResult(List.of(), consumedBeforePage,
                            page.pageNum(), page.pageSize(), true);
                }
                cursor = chunk.nextCursor();
                break;
            }
            if (chunk.nextCursor() == null) {
                return new BusinessLogPageResult(List.of(), consumedBeforePage, page.pageNum(), page.pageSize(), true);
            }
            cursor = chunk.nextCursor();
        }

        while (remainingPageSize > 0) {
            int chunkSize = Math.min(200, remainingPageSize);
            BusinessLogReadPage chunk = reader.apply(withCursor(query, cursor, chunkSize));
            events.addAll(chunk.events());
            remainingPageSize -= chunk.events().size();
            nextCursor = chunk.nextCursor();
            if (nextCursor == null || chunk.events().isEmpty()) {
                break;
            }
            cursor = nextCursor;
        }
        boolean totalKnown = nextCursor == null;
        long visibleThroughPage = consumedBeforePage + events.size();
        long total = totalKnown ? visibleThroughPage : visibleThroughPage + 1;
        return new BusinessLogPageResult(events, total, page.pageNum(), page.pageSize(), totalKnown);
    }

    private static BusinessLogQuery withCursor(BusinessLogQuery query, BusinessLogCursor cursor, int limit) {
        return new BusinessLogQuery(query.occurredFrom(), query.occurredTo(), query.tenantId(), query.eventTypes(),
                query.operatorId(), query.operatorOrganizationIds(), query.moduleAlias(), query.actionCode(),
                query.errorCode(), query.loginOutcome(), query.httpStatus(), cursor, limit);
    }

    private Optional<BusinessLogEvent> findVisibleDetail(String eventId, BusinessLogReadScope scope,
                                                          Set<BusinessLogEventType> allowedTypes) {
        BusinessLogReadScope resolvedScope = requireScope(scope);
        return store.findById(requireEventId(eventId))
                .filter(event -> allowedTypes.contains(event.eventType()))
                .filter(resolvedScope::allows);
    }

    private static BusinessLogQuery constrain(BusinessLogQuery query, BusinessLogReadScope scope,
                                              Set<BusinessLogEventType> allowedTypes) {
        Objects.requireNonNull(query, "query must not be null");
        Set<BusinessLogEventType> eventTypes = query.eventTypes() == null
                ? allowedTypes
                : intersection(query.eventTypes(), allowedTypes);
        BusinessLogQuery typeConstrained = new BusinessLogQuery(query.occurredFrom(), query.occurredTo(),
                query.tenantId(), eventTypes, query.operatorId(), query.operatorOrganizationIds(),
                query.moduleAlias(), query.actionCode(), query.errorCode(), query.loginOutcome(), query.httpStatus(),
                query.cursor(), query.limit());
        return requireScope(scope).constrain(typeConstrained);
    }

    private static BusinessLogStatisticsQuery constrain(BusinessLogStatisticsQuery query, BusinessLogReadScope scope) {
        Objects.requireNonNull(query, "query must not be null");
        return requireScope(scope).constrain(query);
    }

    private static BusinessLogReadScope requireScope(BusinessLogReadScope scope) {
        return Objects.requireNonNull(scope, "scope must not be null");
    }

    private static String requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.trim().length() > 128) {
            throw new IllegalArgumentException("eventId must be between 1 and 128 characters");
        }
        return eventId.trim();
    }

    private static Set<BusinessLogEventType> intersection(Set<BusinessLogEventType> requested,
                                                            Set<BusinessLogEventType> allowed) {
        return requested.stream().filter(allowed::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
