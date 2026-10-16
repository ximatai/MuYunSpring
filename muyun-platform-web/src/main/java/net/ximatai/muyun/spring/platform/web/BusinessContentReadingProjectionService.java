package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.content.BusinessContentTextPresentation;
import net.ximatai.muyun.spring.platform.content.BusinessContentQueryService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Component;

import java.util.*;

/** Batch reading projection over authorized, already exposed raw values; does not read or authorize business records. */
@Component
public final class BusinessContentReadingProjectionService {
    private final BusinessContentQueryService content;
    private final NamedParameterJdbcOperations jdbc;
    private final ObjectMapper mapper;

    public BusinessContentReadingProjectionService(BusinessContentQueryService content,
            NamedParameterJdbcOperations jdbc, ObjectMapper mapper) {
        this.content = Objects.requireNonNull(content);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public Set<String> fields(CrudAbility<?> owner) { return content.fields(owner); }

    public Map<String, Map<String, String>> readings(CrudAbility<?> owner, List<?> records,
            Set<String> allowedFields, String language) {
        if (records == null || records.isEmpty() || allowedFields == null || allowedFields.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Map<String, String>>();
        List<Map<?, ?>> output = records.stream().<Map<?, ?>>map(record -> mapper.convertValue(record, Map.class)).toList();
        for (String field : content.fields(owner)) {
            if (!allowedFields.contains(field)) continue;
            var ids = new LinkedHashSet<String>();
            var originals = new HashMap<String, String>();
            var ambiguous = new HashSet<String>();
            for (Map<?, ?> record : output) {
                Object id = record.get("id");
                Object text = record.containsKey("values") && record.get("values") instanceof Map<?, ?> values
                        ? values.get(field) : record.get(field);
                if (id instanceof String identity && text instanceof String raw) {
                    String previous = originals.putIfAbsent(identity, raw);
                    if (previous != null && !previous.equals(raw)) ambiguous.add(identity);
                    ids.add(identity);
                }
            }
            ids.removeAll(ambiguous);
            if (ids.isEmpty()) continue;
            var projection = content.readingProjection(owner, field, language, "content_read_");
            var params = new LinkedHashMap<>(projection.getParams()); params.put("content_read_ids", ids);
            jdbc.query("select record_id, original_text, reading_text from (" + projection.getSql()
                    + ") reading where record_id in (:content_read_ids)", params, row -> {
                if (!Objects.equals(originals.get(row.getString("record_id")), row.getString("original_text"))) return;
                result.computeIfAbsent(row.getString("record_id"), ignored -> new LinkedHashMap<>())
                        .put(field, row.getString("reading_text"));
            });
        }
        return result;
    }

    public Map<String, Map<String, BusinessContentTextPresentation>> presentations(CrudAbility<?> owner,
            Map<String, Map<String, Object>> authorized, Set<String> allowedFields, Collection<String> languages) {
        if (authorized == null || authorized.isEmpty() || allowedFields == null || allowedFields.isEmpty()
                || languages == null || languages.isEmpty()) return Map.of();
        var fields = new LinkedHashSet<>(content.fields(owner)); fields.retainAll(allowedFields);
        if (fields.isEmpty()) return Map.of();
        List<Map<String, Object>> records = authorized.entrySet().stream().map(entry -> {
            var record = new LinkedHashMap<>(entry.getValue()); record.put("id", entry.getKey()); return (Map<String, Object>) record;
        }).toList();
        Map<String, Map<String, Map<String, String>>> collected = new LinkedHashMap<>();
        for (String language : new LinkedHashSet<>(languages)) readings(owner, records, fields, language).forEach((id, values) ->
                values.forEach((field, text) -> collected.computeIfAbsent(id, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(field, ignored -> new LinkedHashMap<>()).put(language, text)));
        Map<String, Map<String, BusinessContentTextPresentation>> result = new LinkedHashMap<>();
        collected.forEach((id, values) -> {
            Map<String, BusinessContentTextPresentation> texts = new LinkedHashMap<>();
            values.forEach((field, readings) -> {
                Object original = authorized.getOrDefault(id, Map.of()).get(field);
                if (original instanceof String text) texts.put(field, new BusinessContentTextPresentation(text, readings));
            });
            if (!texts.isEmpty()) result.put(id, Map.copyOf(texts));
        });
        return Map.copyOf(result);
    }
}
