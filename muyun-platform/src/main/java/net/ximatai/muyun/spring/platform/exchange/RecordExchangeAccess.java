package net.ximatai.muyun.spring.platform.exchange;

import net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput;
import net.ximatai.muyun.spring.platform.content.BusinessContentService;
import net.ximatai.muyun.spring.platform.content.BusinessContentTranslationService;

import java.time.Instant;
import java.util.*;

/**
 * Each invocation re-enters the producer's live tenant, field, record and business-state
 * boundaries.
 */
public interface RecordExchangeAccess {
    RecordExchangeDefinition definition();

    default void requireNewRowRead(String entity, String parentId, Set<String> fields) {
        throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException(
                "Unpersisted file rows require the producer's current creation scope");
    }

    String inputValue(String entity, String field, String value, String language);

    Object outputValue(String entity, String field, Object value, String language);

    default List<String> options(String entity, String field, String language) {
        return List.of();
    }

    Row read(String entity, String parentId, String id);

    List<Row> match(String entity, String parentId, String field, Object value);

    List<Row> children(String entity, String parentId, int limit);

    Row save(
            String entity,
            String parentId,
            Row existing,
            Map<String, Object> changes,
            Map<String, SourceInput> languages);

    record AggregateChange(
            String key,
            String entity,
            Row existing,
            Map<String, Object> changes,
            Map<String, SourceInput> languages,
            String operation) {}

    /**
     * An aggregate is a single business mutation and transaction, including its formulas and child
     * lifecycle.
     */
    default Map<String, Row> saveAggregate(AggregateChange root, List<AggregateChange> children) {
        if (!children.isEmpty())
            throw new IllegalArgumentException(
                    "This producer does not support aggregate file saves");
        Row row =
                Set.of("SKIP", "CONTEXT").contains(root.operation())
                        ? root.existing()
                        : save(
                                root.entity(),
                                null,
                                root.existing(),
                                root.changes(),
                                root.languages());
        return Map.of(root.key(), row);
    }

    Reference resolve(
            String entity,
            String parentId,
            String field,
            String input,
            String language,
            Map<String, Object> values);

    String referenceLabel(
            String entity,
            String parentId,
            String field,
            Object ids,
            String language,
            Map<String, Object> values);

    void requireReference(
            String entity, String parentId, String field, Object ids, Map<String, Object> values);

    BusinessContentService.Sources sources(String entity, String parentId, String id);

    BusinessContentTranslationService.Reading reading(
            String entity, String parentId, String id, String language);

    BusinessContentTranslationService.Maintenance translation(
            String entity,
            String parentId,
            String id,
            String field,
            String language,
            BusinessContentTranslationService.Change change);

    record Row(String id, Integer version, Instant createdAt, Map<String, Object> values) {
        public Row {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    record Candidate(String id, String title, String matchedText, String kind, String subtitle) {
        public Candidate(String id, String title, String matchedText, String kind) {
            this(id, title, matchedText, kind, null);
        }
    }

    record Reference(List<Candidate> candidates) {
        public Reference {
            candidates = List.copyOf(candidates);
        }
    }
}
