package net.ximatai.muyun.spring.ability.logging;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Storage-neutral append and read boundary for business logging facts. */
public interface BusinessLogStore {
    BusinessLogWriteResult append(BusinessLogEvent event);

    List<BusinessLogWriteResult> appendAll(Collection<? extends BusinessLogEvent> events);

    BusinessLogReadPage read(BusinessLogQuery query);

    /**
     * Reads distinct operator accounts directly from the authorized log stream. Implementations
     * must apply the supplied query before grouping, rather than deriving candidates from one
     * already-paged event result.
     */
    default BusinessLogOperatorCandidatePage readOperatorCandidates(BusinessLogQuery query,
                                                                     BusinessLogOperatorCandidateQuery candidateQuery) {
        throw new UnsupportedOperationException("Business-log operator candidates are not supported");
    }

    /**
     * Reads distinct event-time tenant, organization and department values from the authorized
     * operator stream. Values are identifiers only; an optional ability-level directory lookup
     * may provide display labels after the log range has been fixed.
     */
    default BusinessLogOperatorNavigation readOperatorNavigation(BusinessLogQuery query) {
        throw new UnsupportedOperationException("Business-log operator navigation is not supported");
    }

    /**
     * Optionally loads one event by its storage id. Authorization remains the responsibility of
     * the caller. Existing append/read adapters may opt out of detail retrieval while they are
     * migrated to this capability.
     */
    default Optional<BusinessLogEvent> findById(String eventId) {
        return Optional.empty();
    }
}
