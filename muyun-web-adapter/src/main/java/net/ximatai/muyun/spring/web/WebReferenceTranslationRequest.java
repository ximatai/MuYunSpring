package net.ximatai.muyun.spring.web;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Bounded persisted-ID translation request for a target navigator reference.
 *
 * <p>The IDs are not ordinary query fields: they identify already-selected values and are
 * constrained server-side by the REFERENCE action's data scope.</p>
 */
public record WebReferenceTranslationRequest(List<String> ids) {
    public static final int MAXIMUM_IDS = 100;

    public WebReferenceTranslationRequest {
        ids = ids == null ? List.of() : ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        if (ids.size() > MAXIMUM_IDS) {
            throw new IllegalArgumentException("navigator reference translation supports at most " + MAXIMUM_IDS + " IDs");
        }
    }
}
