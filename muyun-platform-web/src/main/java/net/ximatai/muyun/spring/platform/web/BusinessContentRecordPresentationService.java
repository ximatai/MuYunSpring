package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.child.StaticChildResolver;
import net.ximatai.muyun.spring.ability.output.RecordOutputContext;
import net.ximatai.muyun.spring.ability.output.RecordOutputTransformer;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceTextReadListener;
import net.ximatai.muyun.spring.common.content.BusinessContentTextPresentation;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.web.BusinessContentQueryWebContext;
import net.ximatai.muyun.spring.web.WebReferenceResolveItem;
import net.ximatai.muyun.spring.web.WebReferenceResolveResponse;
import net.ximatai.muyun.spring.web.WebReferenceResolveResult;
import net.ximatai.muyun.spring.web.WebTreeNode;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadReader;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateSubtitleReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.*;

/** Projects business readings from authorized output facts; never changes domain values or re-reads reference targets. */
@Component
@Order(60)
public final class BusinessContentRecordPresentationService implements ReferenceTextReadListener, RecordOutputTransformer {
    private static final String ATTRIBUTE = BusinessContentRecordPresentationService.class.getName();
    private final ObjectMapper mapper;
    private final BusinessContentReadingProjectionService readings;
    private final ObjectProvider<StaticAbilityCatalog> statics;
    private final ObjectProvider<DynamicRecordService> dynamics;

    public BusinessContentRecordPresentationService(ObjectMapper mapper, BusinessContentReadingProjectionService readings,
            ObjectProvider<StaticAbilityCatalog> statics, ObjectProvider<DynamicRecordService> dynamics) {
        this.mapper = mapper; this.readings = readings; this.statics = statics; this.dynamics = dynamics;
    }

    @Override public void onProjection(Projection projection) {
        var snapshot = snapshot(true);
        if (snapshot == null || projection.texts().isEmpty()) return;
        String id = identity(projection.owner());
        if (id == null) return;
        registerOwner(snapshot, projection.owner());
        var key = new Key(id, projection.summaryField(), projection.summaryItemId(), projection.outputField());
        if (snapshot.ambiguous.contains(key)) return;
        var captured = new Captured(projection.sourceField(), projection.sourceIds(), projection.collection(), projection.texts());
        var previous = snapshot.projections.putIfAbsent(key, captured);
        if (previous != null && !previous.equals(captured)) {
            snapshot.projections.remove(key); snapshot.ambiguous.add(key);
        }
    }

    @Override public void onCandidateSubtitles(CandidateSubtitles subtitles) {
        var snapshot = snapshot(true);
        if (snapshot == null) return;
        for (String id : subtitles.candidateIds()) {
            var key = new CandidateKey(subtitles.target(), subtitles.projection(), id);
            if (snapshot.ambiguousSubtitles.contains(key)) continue;
            var value = subtitles.sources().get(id);
            var previous = value == null ? snapshot.subtitles.remove(key) : snapshot.subtitles.putIfAbsent(key, value);
            if (value == null || previous != null && !previous.equals(value)) {
                snapshot.subtitles.remove(key); snapshot.ambiguousSubtitles.add(key);
            }
        }
    }

    /** Candidate titles and captions remain raw; their independent labels use the same authorized identity facts. */
    public WebReferenceResolveResponse projectReference(ReferenceTarget target, String labelField,
            String subtitlePath, WebReferenceResolveResponse response) {
        var snapshot = snapshot(true);
        if (snapshot == null || response == null) return response;
        List<WebReferenceResolveItem> items = new ArrayList<>(response.options());
        for (var result : response.results()) {
            if (result.item() != null) items.add(result.item());
            items.addAll(result.candidates());
        }
        collectItems(response.tree(), items);
        List<Captured> sources = new ArrayList<>();
        for (var item : items) {
            if (!"id".equals(labelField) && item.title() != null && !item.title().isBlank())
                sources.add(new Captured(null, List.of(), false, List.of(new Text(target, item.id(), labelField, item.title()))));
            var subtitle = subtitlePath == null ? null : snapshot.subtitles.get(new CandidateKey(target, subtitlePath, item.id()));
            if (subtitle != null && subtitle.value() instanceof String text
                    && Objects.equals(item.subtitle(), ReferenceCandidateSubtitleReader.label(text)))
                sources.add(new Captured(null, List.of(), false, List.of(new Text(subtitle.target(), subtitle.recordId(), subtitle.field(), text))));
        }
        var prepared = prepare(sources, snapshot.languages);
        java.util.function.UnaryOperator<WebReferenceResolveItem> project = item -> {
            if (item == null) return null;
            var title = prepared.getOrDefault(target, Map.of()).getOrDefault(item.id(), Map.of()).get(labelField);
            if (title != null && !title.originalText().equals(item.title())) title = null;
            var origin = subtitlePath == null ? null : snapshot.subtitles.get(new CandidateKey(target, subtitlePath, item.id()));
            BusinessContentTextPresentation subtitle = null;
            if (origin != null && origin.value() instanceof String original
                    && Objects.equals(item.subtitle(), ReferenceCandidateSubtitleReader.label(original))) {
                var text = prepared.getOrDefault(origin.target(), Map.of()).getOrDefault(origin.recordId(), Map.of()).get(origin.field());
                if (text != null && text.originalText().equals(original)) {
                    Map<String, String> labels = new LinkedHashMap<>();
                    text.readings().forEach((language, value) -> {
                        String label = ReferenceCandidateSubtitleReader.label(value);
                        if (label != null) labels.put(language, label);
                    });
                    if (!labels.isEmpty()) subtitle = new BusinessContentTextPresentation(item.subtitle(), labels);
                }
            }
            return item.withContentTexts(title, subtitle);
        };
        return new WebReferenceResolveResponse(response.status(), response.mode(), response.options().stream().map(project).toList(),
                response.results().stream().map(result -> new WebReferenceResolveResult(result.input(), result.status(),
                        result.matchedBy(), project.apply(result.item()), result.candidates().stream().map(project).toList())).toList(),
                response.offset(), response.limit(), response.total(), projectTree(response.tree(), project));
    }

    private static void collectItems(List<WebTreeNode<WebReferenceResolveItem>> nodes, List<WebReferenceResolveItem> output) {
        for (var node : nodes) { output.add(node.record()); collectItems(node.children(), output); }
    }

    private static List<WebTreeNode<WebReferenceResolveItem>> projectTree(List<WebTreeNode<WebReferenceResolveItem>> nodes,
            java.util.function.UnaryOperator<WebReferenceResolveItem> project) {
        return nodes.stream().map(node -> new WebTreeNode<>(project.apply(node.record()), projectTree(node.children(), project))).toList();
    }

    @Override public boolean supports(CrudAbility<?> owner, RecordOutputContext context) {
        return context.surface() != net.ximatai.muyun.spring.ability.output.RecordOutputSurface.EXPORT
                && context.surface() != net.ximatai.muyun.spring.ability.output.RecordOutputSurface.AUDIT && snapshot(true) != null;
    }

    @Override public <T extends EntityContract> T transformRecord(CrudAbility<T> owner, T record, RecordOutputContext context) {
        var snapshot = snapshot(true);
        if (snapshot != null && record != null) {
            registerOwner(snapshot, record);
            captureOwn(owner, record);
        }
        return record;
    }

    /** The Web adapter owns whether dynamic records are delivered as envelopes or flat expansion rows. */
    public void captureDynamic(String moduleAlias, List<DynamicRecord> records, boolean flat) {
        var snapshot = snapshot(true);
        if (snapshot == null || records == null) return;
        for (var record : records) {
            if (record == null || record.getId() == null) continue;
            var kind = flat ? Kind.FLAT_DYNAMIC : Kind.DYNAMIC;
            var previous = snapshot.explicitKinds.putIfAbsent(record.getId(), kind);
            if (previous != null && previous != kind) snapshot.ambiguousOwners.add(record.getId());
            registerOwner(snapshot, record);
            if (moduleAlias != null && dynamics.getIfAvailable() != null)
                captureOwn(dynamics.getIfAvailable().entity(moduleAlias, record.getEntity().alias()), record);
            record.getChildren().values().forEach(children -> captureDynamic(moduleAlias, children, flat));
        }
    }

    private void captureOwn(CrudAbility<?> owner, EntityContract record) {
        if (record.getId() == null) return;
        var fields = readings.fields(owner);
        if (fields.isEmpty()) return;
        ReferenceTarget target = ReferenceTargets.of(owner);
        for (String field : fields) {
            Object raw = record instanceof DynamicRecord dynamic ? dynamic.getValue(field)
                    : StaticReferenceResolver.readLoadedValue(record, field);
            if (raw instanceof String text) onProjection(new Projection(record, "id", List.of(record.getId()), field,
                    null, null, false, List.of(new Text(target, record.getId(), field, text))));
        }
    }

    public Object projectCaptured(Object body) {
        var snapshot = snapshot(false);
        if (snapshot == null || snapshot.projections.isEmpty() || body == null) return body;
        JsonNode output = mapper.valueToTree(body);
        Map<String, List<Map.Entry<Key, Captured>>> byOwner = new LinkedHashMap<>();
        snapshot.projections.entrySet().forEach(entry -> byOwner.computeIfAbsent(entry.getKey().ownerId(), ignored -> new ArrayList<>()).add(entry));
        List<Delivered> delivered = new ArrayList<>();
        collectBody(body, output, snapshot, byOwner, delivered);
        if (delivered.isEmpty()) return body;
        var prepared = prepare(delivered.stream().map(Delivered::source).distinct().toList(), snapshot.languages);
        if (prepared.isEmpty()) return body;
        delivered.forEach(item -> apply(item.record(), item.values(), item.field(), item.source(), prepared));
        return output;
    }

    private Map<ReferenceTarget, Map<String, Map<String, BusinessContentTextPresentation>>> prepare(
            Collection<Captured> projections, List<String> languages) {
        Map<ReferenceTarget, TargetRequest> targets = new LinkedHashMap<>();
        for (var projection : projections) for (var text : projection.texts()) {
            var target = targets.computeIfAbsent(text.target(), ignored -> new TargetRequest());
            String key = text.recordId() + ":" + text.field();
            if (target.ambiguous.contains(key)) continue;
            var fields = target.originals.computeIfAbsent(text.recordId(), ignored -> new LinkedHashMap<>());
            Object previous = fields.putIfAbsent(text.field(), text.originalText());
            if (previous != null && !previous.equals(text.originalText())) {
                fields.remove(text.field()); target.ambiguous.add(key);
            }
            target.fields.add(text.field());
        }
        var result = new LinkedHashMap<ReferenceTarget, Map<String, Map<String, BusinessContentTextPresentation>>>();
        targets.forEach((target, request) -> {
            var owner = owner(target);
            if (owner != null) {
                var values = readings.presentations(owner, request.originals, request.fields, languages);
                if (!values.isEmpty()) result.put(target, values);
            }
        });
        return result;
    }

    private CrudAbility<?> owner(ReferenceTarget target) {
        var catalog = statics.getIfAvailable();
        var owner = catalog == null ? null : catalog.findByTarget(target).orElse(null);
        if (owner != null) return owner;
        var records = dynamics.getIfAvailable();
        return records == null ? null : records.entity(target.moduleAlias(), target.entityAlias());
    }

    /** Only formal response containers expose nested records; arbitrary JSON keys are never traversal instructions. */
    private void collectBody(Object source, JsonNode node, Snapshot snapshot,
            Map<String, List<Map.Entry<Key, Captured>>> byOwner, List<Delivered> output) {
        if (source instanceof net.ximatai.muyun.spring.web.WebPageResponse<?> page)
            collectRecords(page.records(), node.path("records"), snapshot, byOwner, output);
        else if (source instanceof net.ximatai.muyun.spring.web.WebListResponse<?> list)
            collectRecords(list.records(), node.path("records"), snapshot, byOwner, output);
        else if (source instanceof WebTreeNode<?> tree) {
            collectBody(tree.record(), node.path("record"), snapshot, byOwner, output);
            collectRecords(tree.children(), node.path("children"), snapshot, byOwner, output);
        } else if (source instanceof Collection<?> records)
            collectRecords(new ArrayList<>(records), node, snapshot, byOwner, output);
        else if (source instanceof EntityContract || source instanceof Map<?, ?> map && map.get("id") instanceof String)
            collect(node, snapshot, byOwner, output);
    }

    private void collectRecords(List<?> records, JsonNode node, Snapshot snapshot,
            Map<String, List<Map.Entry<Key, Captured>>> byOwner, List<Delivered> output) {
        if (!node.isArray() || records.size() != node.size()) return;
        for (int index = 0; index < records.size(); index++)
            collectBody(records.get(index), node.get(index), snapshot, byOwner, output);
    }

    private void collect(JsonNode node, Snapshot snapshot, Map<String, List<Map.Entry<Key, Captured>>> byOwner,
            List<Delivered> output) {
        if (node == null) return;
        if (node.isArray()) { node.forEach(child -> collect(child, snapshot, byOwner, output)); return; }
        if (!(node instanceof ObjectNode record)) return;
        String id = record.path("id").isTextual() ? record.path("id").asText() : null;
        if (id == null) return;
        if (snapshot.ambiguousOwners.contains(id)) return;
        var shape = snapshot.shapes.get(id);
        if (shape == null) return;
        var kind = snapshot.explicitKinds.getOrDefault(id, shape.kind);
        if (kind == Kind.DYNAMIC && !(record.get("values") instanceof ObjectNode)) return;
        ObjectNode values = kind == Kind.DYNAMIC ? (ObjectNode) record.get("values") : record;
        for (var entry : byOwner.getOrDefault(id, List.of())) {
            Key key = entry.getKey(); var source = entry.getValue();
            if (!key.ownerId().equals(id) || !sourceMatches(values, source)) continue;
            if (key.summaryField() == null) collectField(record, values, key.field(), source, output);
            else {
                JsonNode summary = values.get(key.summaryField());
                if (summary != null && summary.isArray()) summary.forEach(item -> collectSummary(item, key, source, output));
                else collectSummary(summary, key, source, output);
            }
        }
        if (kind == Kind.DYNAMIC) {
            var children = record.get("children");
            if (children != null && children.isObject()) children.forEach(child -> collect(child, snapshot, byOwner, output));
        } else for (String field : shape.children) collect(record.get(field), snapshot, byOwner, output);
    }

    private void collectSummary(JsonNode node, Key key, Captured source, List<Delivered> output) {
        if (node instanceof ObjectNode item && key.itemId().equals(item.path("id").asText()))
            collectField(item, item, key.field(), source, output);
    }

    private void collectField(ObjectNode record, ObjectNode values, String field, Captured source, List<Delivered> output) {
        Object expected = source.collection() ? source.texts().stream().map(Text::originalText).toList()
                : source.texts().getFirst().originalText();
        if (values.has(field) && values.get(field).equals(mapper.valueToTree(expected)))
            output.add(new Delivered(record, values, field, source));
    }

    private void apply(ObjectNode record, ObjectNode values, String field, Captured source,
            Map<ReferenceTarget, Map<String, Map<String, BusinessContentTextPresentation>>> prepared) {
        JsonNode actual = values.get(field);
        Object expected = source.collection() ? source.texts().stream().map(Text::originalText).toList()
                : source.texts().getFirst().originalText();
        if (actual == null || !actual.equals(mapper.valueToTree(expected))) return;
        var projected = source.texts().stream().map(text -> prepared.getOrDefault(text.target(), Map.of())
                .getOrDefault(text.recordId(), Map.of()).get(text.field())).toList();
        if (projected.stream().allMatch(Objects::isNull)) return;
        var metadata = record.get("$contentTexts") instanceof ObjectNode existing ? existing : mapper.createObjectNode();
        metadata.set(field, mapper.valueToTree(source.collection() ? projected : projected.getFirst()));
        record.set("$contentTexts", metadata);
    }

    private boolean sourceMatches(ObjectNode values, Captured source) {
        if (source.sourceField() == null || !values.has(source.sourceField())) return true;
        var value = values.get(source.sourceField());
        List<String> ids = new ArrayList<>();
        if (value.isArray()) value.forEach(item -> { if (item.isTextual()) ids.add(item.asText()); });
        else if (value.isTextual() && !value.asText().isBlank()) {
            String raw = value.asText();
            if (raw.startsWith("[")) {
                try { var array = mapper.readTree(raw); if (array.isArray()) array.forEach(item -> { if (item.isTextual()) ids.add(item.asText()); }); }
                catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { ids.add(raw); }
            } else ids.add(raw);
        }
        return List.copyOf(new LinkedHashSet<>(ids)).equals(source.sourceIds());
    }

    private void registerOwner(Snapshot snapshot, Object owner) {
        String id = identity(owner);
        if (id == null) return;
        var shape = snapshot.shapes.computeIfAbsent(id, ignored -> new Shape());
        if (!snapshot.registered.add(owner)) return;
        if (owner instanceof DynamicRecord dynamic) {
            shape.kind = Kind.DYNAMIC;
            dynamic.getChildren().values().forEach(children -> children.forEach(child -> registerOwner(snapshot, child)));
        } else if (owner instanceof EntityContract entity) {
            for (var rule : StaticChildResolver.rules(owner.getClass())) {
                shape.children.add(rule.field().getName());
                var children = rule.children(entity);
                if (children != null) children.forEach(child -> registerOwner(snapshot, child));
            }
        }
    }

    private static String identity(Object owner) {
        Object id = owner instanceof EntityContract entity ? entity.getId() : owner instanceof Map<?, ?> map ? map.get("id") : null;
        return id instanceof String value && !value.isBlank() ? value : null;
    }

    private Snapshot snapshot(boolean create) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        var current = (Snapshot) attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (current == null && create) {
            var languages = BusinessContentQueryWebContext.languages();
            if (languages.isEmpty()) return null;
            current = new Snapshot(languages); attributes.setAttribute(ATTRIBUTE, current, RequestAttributes.SCOPE_REQUEST);
        }
        return current;
    }

    private record Key(String ownerId, String summaryField, String itemId, String field) { }
    private record CandidateKey(ReferenceTarget target, String path, String id) { }
    private record Captured(String sourceField, List<String> sourceIds, boolean collection, List<Text> texts) { }
    private record Delivered(ObjectNode record, ObjectNode values, String field, Captured source) { }
    private enum Kind { STATIC, DYNAMIC, FLAT_DYNAMIC }
    private static final class Shape { Kind kind = Kind.STATIC; Set<String> children = new LinkedHashSet<>(); }
    private static final class TargetRequest {
        Map<String, Map<String, Object>> originals = new LinkedHashMap<>(); Set<String> fields = new LinkedHashSet<>(); Set<String> ambiguous = new HashSet<>();
    }
    private static final class Snapshot {
        final List<String> languages;
        final Map<Key, Captured> projections = new LinkedHashMap<>(); final Set<Key> ambiguous = new HashSet<>();
        final Map<String, Shape> shapes = new LinkedHashMap<>(); final Map<String, Kind> explicitKinds = new HashMap<>();
        final Set<String> ambiguousOwners = new HashSet<>();
        final Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<CandidateKey, ReferenceLoadReader.ResolvedValue> subtitles = new LinkedHashMap<>();
        final Set<CandidateKey> ambiguousSubtitles = new HashSet<>();
        Snapshot(List<String> languages) { this.languages = languages; }
    }
}
