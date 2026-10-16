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
    private static final String SNAPSHOT = BusinessContentReadingProjectionService.class.getName();
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

    /** Lifecycle facts are copied from the same authorized rows as the original text, before output masking. */
    public void capture(CrudAbility<?> owner, Collection<?> records) {
        var snapshot = snapshot(true);
        if (snapshot == null || records == null) return;
        var fields = content.fields(owner);
        if (fields.isEmpty()) return;
        var target = net.ximatai.muyun.spring.ability.reference.ReferenceTargets.of(owner);
        for (Object record : records) {
            Map<?, ?> row;
            if (record instanceof net.ximatai.muyun.spring.common.model.contract.EntityContract entity) {
                var values = new LinkedHashMap<String, Object>();
                values.put("id", entity.getId()); values.put("tenantId", entity.getTenantId());
                values.put("createdAt", entity.getCreatedAt()); values.put("updatedAt", entity.getUpdatedAt()); values.put("version", entity.getVersion());
                for (String field : fields) values.put(field, record instanceof net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord dynamic
                        ? dynamic.getValue(field) : net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver.readLoadedValue(record, field));
                row = values;
            } else if (record instanceof Map<?, ?> map) row = map;
            else continue;
            if (!(row.get("id") instanceof String id) || !row.containsKey("tenantId")
                    || !(row.get("version") instanceof Number version)) continue;
            var created = instant(row.get("createdAt"));
            var updated = instant(row.get("updatedAt"));
            if (created == null || updated == null) continue;
            var identity = new Identity((String) row.get("tenantId"), created, updated, version.intValue());
            for (String field : fields) if (row.get(field) instanceof String raw) {
                var key = new ReadKey(target, id, field, raw);
                if (snapshot.ambiguous.contains(key)) continue;
                var previous = snapshot.identities.putIfAbsent(key, identity);
                if (previous != null && !previous.equals(identity)) {
                    snapshot.identities.remove(key); snapshot.ambiguous.add(key);
                }
            }
        }
    }

    public Map<String, Map<String, String>> readings(CrudAbility<?> owner, List<?> records,
            Set<String> allowedFields, String language) {
        if (records == null || records.isEmpty() || allowedFields == null || allowedFields.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Map<String, String>>();
        List<Map<?, ?>> output = records.stream().<Map<?, ?>>map(record -> {
            Map<?, ?> row = mapper.convertValue(record, Map.class);
            if (!(record instanceof net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord)) return row;
            Map<String, Object> flattened = new LinkedHashMap<>();
            if (row.get("values") instanceof Map<?, ?> values) values.forEach((field, value) -> flattened.put((String) field, value));
            flattened.put("id", row.get("id"));
            return flattened;
        }).toList();
        for (String field : content.fields(owner)) {
            if (!allowedFields.contains(field)) continue;
            var ids = new LinkedHashSet<String>();
            var originals = new HashMap<String, String>();
            var ambiguous = new HashSet<String>();
            for (Map<?, ?> record : output) {
                Object id = record.get("id");
                Object text = record.get(field);
                if (id instanceof String identity && text instanceof String raw) {
                    String previous = originals.putIfAbsent(identity, raw);
                    if (previous != null && !previous.equals(raw)) ambiguous.add(identity);
                    ids.add(identity);
                }
            }
            ids.removeAll(ambiguous);
            if (ids.isEmpty()) continue;
            var snapshot = snapshot(false);
            if (snapshot == null) continue;
            var target = net.ximatai.muyun.spring.ability.reference.ReferenceTargets.of(owner);
            ids.removeIf(id -> !snapshot.identities.containsKey(new ReadKey(target, id, field, originals.get(id))));
            if (ids.isEmpty()) continue;
            var orderedIds = new ArrayList<>(ids);
            for (int offset = 0; offset < orderedIds.size(); offset += 256) {
                var batch = orderedIds.subList(offset, Math.min(offset + 256, orderedIds.size()));
                var projection = content.readingProjection(owner, field, language, "content_read_");
                var params = new LinkedHashMap<>(projection.getParams());
                var guard = new StringJoiner(" or ", "(", ")");
                int index = 0;
                for (String id : batch) {
                    var identity = snapshot.identities.get(new ReadKey(target, id, field, originals.get(id)));
                    String prefix = "authorized_read_" + index++ + "_";
                    params.put(prefix + "id", id); params.put(prefix + "created", java.sql.Timestamp.from(identity.createdAt()));
                    params.put(prefix + "updated", java.sql.Timestamp.from(identity.updatedAt()));
                    params.put(prefix + "version", identity.version()); params.put(prefix + "raw", originals.get(id));
                    String tenant = "tenant_id is null";
                    if (identity.tenantId() != null) { params.put(prefix + "tenant", identity.tenantId()); tenant = "tenant_id = :" + prefix + "tenant"; }
                    guard.add("(record_id = :" + prefix + "id and " + tenant + " and record_created_at = :" + prefix
                            + "created and record_updated_at = :" + prefix + "updated and record_version = :" + prefix + "version and original_text = :" + prefix + "raw)");
                }
                jdbc.query("select record_id, tenant_id, record_created_at, record_updated_at, record_version, original_text, reading_text from (" + projection.getSql()
                        + ") reading where " + guard, params, row -> {
                    if (!Objects.equals(originals.get(row.getString("record_id")), row.getString("original_text"))) return;
                    var expected = snapshot.identities.get(new ReadKey(target, row.getString("record_id"), field, row.getString("original_text")));
                    var createdAt = row.getTimestamp("record_created_at");
                    var updatedAt = row.getTimestamp("record_updated_at");
                    if (expected == null || createdAt == null || updatedAt == null || !Objects.equals(expected.tenantId(), row.getString("tenant_id"))
                            || !expected.createdAt().equals(createdAt.toInstant()) || row.getObject("record_version") == null
                            || !expected.updatedAt().equals(updatedAt.toInstant())
                            || expected.version() != row.getInt("record_version")) return;
                    result.computeIfAbsent(row.getString("record_id"), ignored -> new LinkedHashMap<>())
                            .put(field, row.getString("reading_text"));
                });
            }
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

    private static java.time.Instant instant(Object value) {
        if (value instanceof java.time.Instant instant) return instant;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime date) return date.toInstant();
        if (value instanceof String text) try { return java.time.Instant.parse(text); }
        catch (java.time.format.DateTimeParseException ignored) { return null; }
        return null;
    }

    private static ReadSnapshot snapshot(boolean create) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        var snapshot = (ReadSnapshot) attributes.getAttribute(SNAPSHOT, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        if (snapshot == null && create) {
            snapshot = new ReadSnapshot();
            attributes.setAttribute(SNAPSHOT, snapshot, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        }
        return snapshot;
    }
    private record ReadKey(net.ximatai.muyun.spring.ability.reference.ReferenceTarget target, String id, String field, String raw) { }
    private record Identity(String tenantId, java.time.Instant createdAt, java.time.Instant updatedAt, int version) { }
    private static final class ReadSnapshot {
        final Map<ReadKey, Identity> identities = new HashMap<>();
        final Set<ReadKey> ambiguous = new HashSet<>();
    }
}
