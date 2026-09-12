package net.ximatai.muyun.spring.ability.logging;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A resolved administrative visibility range.
 *
 * <p>Organization scopes apply to the immutable organization snapshot recorded on an event,
 * never to the operator's current organization assignment.</p>
 */
public record BusinessLogReadScope(
        BusinessLogReadScopeType scopeType,
        String tenantId,
        Set<String> operatorOrganizationIds,
        boolean permitted
) {
    public BusinessLogReadScope {
        scopeType = Objects.requireNonNull(scopeType, "scopeType must not be null");
        tenantId = BusinessLogContext.optional(tenantId, "tenantId", 128);
        operatorOrganizationIds = normalizeOrganizationIds(operatorOrganizationIds);
        if (!permitted) {
            if (scopeType != BusinessLogReadScopeType.ORGANIZATION || tenantId != null
                    || !operatorOrganizationIds.isEmpty()) {
                throw new IllegalArgumentException("denied scope must not carry a visibility range");
            }
        } else {
            switch (scopeType) {
                case PLATFORM -> {
                    if (tenantId != null || !operatorOrganizationIds.isEmpty()) {
                        throw new IllegalArgumentException("platform scope must not carry tenant or organization filters");
                    }
                }
                case TENANT -> {
                    if (tenantId == null || !operatorOrganizationIds.isEmpty()) {
                        throw new IllegalArgumentException("tenant scope requires only a tenantId");
                    }
                }
                case ORGANIZATION -> {
                    if (tenantId == null || operatorOrganizationIds.isEmpty()) {
                        throw new IllegalArgumentException("organization scope requires tenantId and organization ids");
                    }
                }
            }
        }
    }

    public static BusinessLogReadScope platform() {
        return new BusinessLogReadScope(BusinessLogReadScopeType.PLATFORM, null, Set.of(), true);
    }

    public static BusinessLogReadScope tenant(String tenantId) {
        return new BusinessLogReadScope(BusinessLogReadScopeType.TENANT, tenantId, Set.of(), true);
    }

    public static BusinessLogReadScope organization(String tenantId, Set<String> operatorOrganizationIds) {
        return new BusinessLogReadScope(BusinessLogReadScopeType.ORGANIZATION, tenantId, operatorOrganizationIds, true);
    }

    /** A query-safe range that matches no event. */
    public static BusinessLogReadScope denied() {
        return new BusinessLogReadScope(BusinessLogReadScopeType.ORGANIZATION, null, Set.of(), false);
    }

    public boolean isPlatformScope() {
        return permitted && scopeType == BusinessLogReadScopeType.PLATFORM;
    }

    /**
     * Intersects caller-provided filters with this immutable administrative range.
     * A contradictory caller filter becomes a query that matches no events.
     */
    public BusinessLogQuery constrain(BusinessLogQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (!permitted) {
            return copy(query, query.tenantId(), Set.of());
        }
        if (scopeType == BusinessLogReadScopeType.PLATFORM) {
            return query;
        }
        if (!tenantId.equals(query.tenantId()) && query.tenantId() != null) {
            return copy(query, tenantId, Set.of());
        }
        if (scopeType == BusinessLogReadScopeType.TENANT) {
            return copy(query, tenantId, query.operatorOrganizationIds());
        }
        Set<String> restrictedOrganizations = query.operatorOrganizationIds() == null
                ? operatorOrganizationIds
                : intersection(query.operatorOrganizationIds(), operatorOrganizationIds);
        return copy(query, tenantId, restrictedOrganizations);
    }

    /** Intersects a bounded statistics request with this administrative range. */
    public BusinessLogStatisticsQuery constrain(BusinessLogStatisticsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (!permitted) {
            return copy(query, query.tenantId(), Set.of());
        }
        if (scopeType == BusinessLogReadScopeType.PLATFORM) {
            return query;
        }
        if (!tenantId.equals(query.tenantId()) && query.tenantId() != null) {
            return copy(query, tenantId, Set.of());
        }
        if (scopeType == BusinessLogReadScopeType.TENANT) {
            return copy(query, tenantId, query.operatorOrganizationIds());
        }
        Set<String> restrictedOrganizations = query.operatorOrganizationIds() == null
                ? operatorOrganizationIds
                : intersection(query.operatorOrganizationIds(), operatorOrganizationIds);
        return copy(query, tenantId, restrictedOrganizations);
    }

    /** Returns whether a stored event itself belongs to this range. */
    public boolean allows(BusinessLogEvent event) {
        if (!permitted || event == null) {
            return false;
        }
        if (scopeType == BusinessLogReadScopeType.PLATFORM) {
            return true;
        }
        if (!tenantId.equals(event.context().tenantId())) {
            return false;
        }
        return scopeType == BusinessLogReadScopeType.TENANT
                || operatorOrganizationIds.contains(event.context().operatorOrganizationId());
    }

    private static BusinessLogQuery copy(BusinessLogQuery query, String tenantId, Set<String> organizationIds) {
        return new BusinessLogQuery(query.occurredFrom(), query.occurredTo(), tenantId, query.eventTypes(),
                query.operatorId(), organizationIds, query.moduleAlias(), query.actionCode(), query.errorCode(),
                query.loginAccount(), query.loginOutcome(), query.httpStatus(), query.cursor(), query.limit());
    }

    private static BusinessLogStatisticsQuery copy(BusinessLogStatisticsQuery query, String tenantId,
                                                   Set<String> organizationIds) {
        return new BusinessLogStatisticsQuery(query.occurredFrom(), query.occurredTo(), tenantId,
                query.operatorId(), organizationIds, query.moduleAlias(), query.actionCode(),
                query.maximumEvents());
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        LinkedHashSet<String> values = new LinkedHashSet<>(left);
        values.retainAll(right);
        return Set.copyOf(values);
    }

    private static Set<String> normalizeOrganizationIds(Set<String> values) {
        Set<String> normalized = BusinessLogQuery.normalizeIds(values, "operatorOrganizationIds");
        return normalized == null ? Set.of() : normalized;
    }
}
