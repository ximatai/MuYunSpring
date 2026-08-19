package net.ximatai.muyun.spring.common.platform;

import java.util.Optional;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public interface RecordActionAvailabilityContributor {
    Optional<RecordActionAvailabilityDecision> availability(String moduleAlias,
                                                            String actionCode,
                                                            String recordId);

    /**
     * Explorer projections may request availability for several visible records. Contributors
     * that need a native batch read can override this method; the default preserves the
     * established single-record extension contract.
     */
    default Map<String, Optional<RecordActionAvailabilityDecision>> availability(String moduleAlias,
                                                                                   String actionCode,
                                                                                   Collection<String> recordIds) {
        Map<String, Optional<RecordActionAvailabilityDecision>> decisions = new LinkedHashMap<>();
        if (recordIds == null) {
            return decisions;
        }
        for (String recordId : recordIds) {
            decisions.put(recordId, availability(moduleAlias, actionCode, recordId));
        }
        return Map.copyOf(decisions);
    }
}
