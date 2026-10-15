package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.option.*;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.platform.localization.RecordTextSourceAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.output.RecordOutputTransformer;
import net.ximatai.muyun.spring.ability.output.RecordOutputContext;
import org.springframework.core.annotation.Order;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.web.WebTreeNode;
import net.ximatai.muyun.spring.web.WebReferenceResolveResult;
import net.ximatai.muyun.spring.web.WebReferenceResolveItem;
import net.ximatai.muyun.spring.web.WebReferenceResolveResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** Projects only producer-owned labels already present in authorized read records. */
@Component
@Order(50)
public class RecordTextPresentationService implements RecordOutputTransformer {
    private static final String SOURCES_ATTRIBUTE = RecordTextPresentationService.class.getName() + ".sources";
    private final ObjectMapper mapper;
    private final ObjectProvider<StaticAbilityCatalog> abilities;
    private final ObjectProvider<TextResolver> resolvers;
    private final ObjectProvider<LanguageCatalog> catalogs;
    private final ObjectProvider<OptionSourceRegistry> options;
    public RecordTextPresentationService(ObjectMapper mapper, ObjectProvider<StaticAbilityCatalog> abilities,
            ObjectProvider<TextResolver> resolvers, ObjectProvider<LanguageCatalog> catalogs, ObjectProvider<OptionSourceRegistry> options) {
        this.mapper = mapper; this.abilities = abilities; this.resolvers = resolvers; this.catalogs = catalogs; this.options = options;
    }
    @Override
    public boolean supports(CrudAbility<?> service, RecordOutputContext context) {
        return RequestContextHolder.getRequestAttributes() != null && supportsSources(service);
    }

    /** Captures producer-owned sources from records that the read pipeline has already authorized. */
    @Override
    public <T extends EntityContract> T transformRecord(CrudAbility<T> service, T record, RecordOutputContext context) {
        if (record != null) transformRecords(service, List.of(record), context);
        return record;
    }

    @Override
    public <T extends EntityContract> List<T> transformRecords(CrudAbility<T> service, List<T> records, RecordOutputContext context) {
        if (records == null || records.isEmpty() || !supports(service, context)) return records;
        Map<String, Map<String, TextReference>> sources = new LinkedHashMap<>();
        if (service instanceof RecordTextSourceAbility<?> sourceAbility) {
            @SuppressWarnings("unchecked") var typed = (RecordTextSourceAbility<T>) sourceAbility;
            for (T record : records) sources.put(record.getId(), new LinkedHashMap<>(typed.recordTextSources(record)));
        }
        List<ObjectNode> output = new ArrayList<>(); collectRecords(mapper.valueToTree(records), output);
        addOptionSources(service, output, sources);
        capture(sources);
        captureSummaries(service, output);
        return records;
    }

    /** SQL projections preserve the actual producer, visibility and tenant before leaving the read scope. */
    void captureProjected(CrudAbility<?> ability, List<Map<String, Object>> records, RecordReadVisibility visibility) {
        if (RequestContextHolder.getRequestAttributes() == null || !supportsSources(ability)) return;
        capture(recordSources(ability, mapper.valueToTree(records), visibility));
        List<ObjectNode> output = new ArrayList<>(); collectRecords(mapper.valueToTree(records), output);
        captureSummaries(ability, output);
    }

    public Object projectCaptured(Object body) {
        var attributes = RequestContextHolder.getRequestAttributes();
        var sources = attributes == null ? null : (ResponseSources) attributes.getAttribute(SOURCES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return sources == null ? body : render(body, sources.fields, sources.summaries);
    }

    private static void capture(Map<String, Map<String, TextReference>> sources) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return;
        var snapshot = (ResponseSources) attributes.getAttribute(SOURCES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (snapshot == null) {
            snapshot = new ResponseSources();
            attributes.setAttribute(SOURCES_ATTRIBUTE, snapshot, RequestAttributes.SCOPE_REQUEST);
        }
        snapshot.add(sources);
    }

    /** Different producers with coinciding IDs must never borrow each other's labels. */
    private static final class ResponseSources {
        private final Map<String, Map<String, TextReference>> fields = new LinkedHashMap<>();
        private final Map<String, Set<String>> ambiguous = new HashMap<>();
        private final Map<SummarySourceKey, Map<String, TextReference>> summaries = new LinkedHashMap<>();
        private final Set<SummarySourceKey> ambiguousSummaries = new HashSet<>();
        void addSummary(SummarySourceKey key, Map<String, TextReference> sources) {
            if (ambiguousSummaries.contains(key)) return;
            var previous = summaries.putIfAbsent(key, sources);
            if (previous != null && !previous.equals(sources)) {
                summaries.remove(key); ambiguousSummaries.add(key);
            }
        }
        void add(Map<String, Map<String, TextReference>> sources) {
            sources.forEach((id, texts) -> texts.forEach((field, source) -> {
                if (ambiguous.getOrDefault(id, Set.of()).contains(field)) return;
                var row = fields.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
                var previous = row.putIfAbsent(field, source);
                if (previous != null && !previous.equals(source)) {
                    row.remove(field);
                    ambiguous.computeIfAbsent(id, ignored -> new HashSet<>()).add(field);
                }
            }));
        }
    }

    private static boolean supportsSources(CrudAbility<?> ability) {
        return ability instanceof RecordTextSourceAbility<?> || !OptionLoadResolver.resolve(ability.modelClass()).isEmpty()
                || !StaticReferenceResolver.summaryPlans(ability.modelClass()).isEmpty();
    }

    /** Reference callers invoke this while still inside their authorized target read scope. */
    public Object project(String moduleAlias, Object body) {
        var registry = abilities.getIfAvailable();
        if (body == null || registry == null) return body;
        var ability = registry.abilities().stream().filter(service -> moduleAlias.equals(service.getModuleAlias()))
                .findFirst().orElse(null);
        return ability == null || !supportsSources(ability) ? body : render(body, recordSources(ability, mapper.valueToTree(body), RecordReadVisibility.ACTIVE), Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, TextReference>> recordSources(CrudAbility<?> ability, JsonNode output, RecordReadVisibility visibility) {
        List<ObjectNode> records = new ArrayList<>(); collectRecords(output, records);
        Set<String> ids = new LinkedHashSet<>(); records.forEach(record -> ids.add(record.path("id").asText()));
        Map<String, Map<String, TextReference>> sources = !ids.isEmpty() && ability instanceof RecordTextSourceAbility<?> sourceAbility
                ? sources((RecordTextSourceAbility<EntityContract>) sourceAbility, ids, visibility) : new LinkedHashMap<>();
        addOptionSources(ability, records, sources);
        return sources;
    }

    private void addOptionSources(CrudAbility<?> ability, List<ObjectNode> records, Map<String, Map<String, TextReference>> sources) {
        var optionRegistry = options.getIfAvailable();
        if (optionRegistry != null) for (var load : OptionLoadResolver.resolve(ability.modelClass())) {
            if (!"title".equals(load.optionItemField()) || load.sourceDefinition().selectionMode() != OptionSelectionMode.SINGLE ||
                    !OptionBinding.ENUM_SOURCE.equals(load.sourceDefinition().binding().sourceType())) continue;
            var items = optionRegistry.source(load.sourceDefinition().binding()).options(OptionQuery.all());
            for (var record : records) {
                if (!record.path(load.sourceField()).isTextual() || !record.path(load.outputField()).isTextual()) continue;
                var code = record.path(load.sourceField()).asText();
                items.stream().filter(item -> item.code().equals(code) && item.titleSource() != null &&
                        item.title().equals(record.path(load.outputField()).asText())).findFirst().ifPresent(item ->
                        sources.computeIfAbsent(record.path("id").asText(), ignored -> new LinkedHashMap<>()).put(load.outputField(), item.titleSource()));
            }
        }
    }

    private record SummarySourceKey(String ownerId, String field, String targetId) {}
    private record PresentationRecord(ObjectNode record, Map<String, TextReference> fields) {}

    /** Only compiler-declared summaries of delivered reference identities are presentation documents. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void captureSummaries(CrudAbility<?> owner, List<ObjectNode> records) {
        var registry = abilities.getIfAvailable();
        var attributes = RequestContextHolder.getRequestAttributes();
        if (registry == null || attributes == null) return;
        capture(Map.of());
        var snapshot = (ResponseSources) attributes.getAttribute(SOURCES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        for (var summary : StaticReferenceResolver.summaryPlans(owner.modelClass())) {
            var target = registry.findByTarget(summary.target()).filter(candidate -> candidate instanceof RecordTextSourceAbility<?>).orElse(null);
            if (target == null) continue;
            var plan = StaticReferenceResolver.plans(owner.modelClass()).stream()
                    .filter(candidate -> candidate.sourceField().equals(summary.sourceField())).findFirst().orElseThrow();
            Map<SummarySourceKey, ObjectNode> delivered = new LinkedHashMap<>();
            for (var record : records) {
                var ids = plan.normalizeValues(mapper.convertValue(record.get(summary.sourceField()), Object.class));
                for (var item : summaryRecords(record.get(summary.outputField()))) {
                    var id = item.path("id").asText();
                    if (ids.contains(id)) delivered.put(new SummarySourceKey(record.path("id").asText(), summary.outputField(), id), item);
                }
            }
            if (delivered.isEmpty()) continue;
            var ids = delivered.keySet().stream().map(SummarySourceKey::targetId).distinct().toList();
            var criteria = Criteria.of().in("id", ids);
            java.util.function.Function<Criteria, Map<String, Map<String, TextReference>>> read = query -> {
                Map<String, Map<String, TextReference>> result = new LinkedHashMap<>();
                var source = (RecordTextSourceAbility<EntityContract>) target;
                for (var record : source.list(query, new PageRequest(0, ids.size())))
                    result.put(record.getId(), source.recordTextSources(record));
                return result;
            };
            // Re-enter the target's REFERENCE scope before its source read, including any tenant switch.
            Map<String, Map<String, TextReference>> texts;
            if (target instanceof DataScopeAbility scoped) {
                var scope = scoped.readScope(PlatformAction.REFERENCE, criteria);
                texts = (Map<String, Map<String, TextReference>>) scoped.withDataScopeTenant(scope, () -> read.apply(scope.criteria()));
            } else texts = read.apply(criteria);
            delivered.forEach((key, record) -> {
                Map<String, TextReference> fields = new LinkedHashMap<>();
                texts.getOrDefault(key.targetId(), Map.of()).forEach((field, source) -> {
                    if (summary.fields().contains(field) && record.path(field).isTextual() && source.originalText().equals(record.path(field).asText())) fields.put(field, source);
                });
                snapshot.addSummary(key, Map.copyOf(fields));
            });
        }
    }

    private static List<ObjectNode> summaryRecords(JsonNode value) {
        if (value instanceof ObjectNode object) return List.of(object);
        List<ObjectNode> records = new ArrayList<>();
        if (value != null && value.isArray()) value.forEach(item -> { if (item instanceof ObjectNode object) records.add(object); });
        return records;
    }

    private Object render(Object body, Map<String, Map<String, TextReference>> sources, Map<SummarySourceKey, Map<String, TextReference>> summaries) {
        var resolver = resolvers.getIfAvailable(); var catalog = catalogs.getIfAvailable();
        if (body == null || resolver == null || catalog == null || sources.isEmpty() && summaries.isEmpty()) return body;
        JsonNode output = mapper.valueToTree(body);
        List<ObjectNode> records = new ArrayList<>(); collectRecords(output, records);
        List<PresentationRecord> presentations = new ArrayList<>();
        for (var record : records) {
            String id = record.path("id").asText();
            presentations.add(new PresentationRecord(record, sources.getOrDefault(id, Map.of())));
            summaries.forEach((key, fields) -> {
                if (!key.ownerId().equals(id)) return;
                for (var item : summaryRecords(record.get(key.field())))
                    if (key.targetId().equals(item.path("id").asText())) presentations.add(new PresentationRecord(item, fields));
            });
        }
        var prepared = resolver.prepare(presentations.stream().flatMap(item -> item.fields().entrySet().stream()
                .filter(entry -> item.record().path(entry.getKey()).isTextual() && item.record().path(entry.getKey()).asText().equals(entry.getValue().originalText()))
                .map(Map.Entry::getValue)).distinct().toList());
        for (var item : presentations) {
            var record = item.record(); var fields = item.fields();
            ObjectNode texts = mapper.createObjectNode();
            fields.forEach((field, source) -> {
                // Never reveal a source for a hidden, changed, masked or non-textual read field.
                if (record.path(field).isTextual() && record.path(field).asText().equals(source.originalText()))
                    texts.set(field, mapper.valueToTree(TextPresentations.of(source, Map.of(), prepared, catalog)));
            });
            if (!texts.isEmpty()) record.set("$texts", texts);
        }
        return output;
    }

    public WebReferenceResolveResponse projectReference(String moduleAlias, String labelField,
            WebReferenceResolveResponse response) {
        if ("id".equals(labelField)) return response;
        var items = new ArrayList<WebReferenceResolveItem>();
        items.addAll(response.options());
        for (var result : response.results()) {
            if (result.item() != null) items.add(result.item());
            items.addAll(result.candidates());
        }
        java.util.function.Consumer<WebTreeNode<WebReferenceResolveItem>> collect =
                new java.util.function.Consumer<>() {
                    @Override public void accept(WebTreeNode<WebReferenceResolveItem> node) {
                        items.add(node.record()); node.children().forEach(this);
                    }
                };
        response.tree().forEach(collect);
        var originals = items.stream().filter(item -> item.title() != null)
                .map(item -> Map.of("id", item.id(), labelField, item.title())).toList();
        JsonNode projected = mapper.valueToTree(project(moduleAlias, originals));
        Map<String, TextPresentation> texts = new HashMap<>();
        for (var row : projected) {
            var text = row.path("$texts").get(labelField);
            if (text != null) texts.put(row.path("id").asText(), mapper.convertValue(text, TextPresentation.class));
        }
        java.util.function.UnaryOperator<WebReferenceResolveItem> display = item -> {
            if (item == null) return null;
            var text = texts.get(item.id());
            return text != null && text.source().originalText().equals(item.title()) ? item.withTitleText(text) : item;
        };
        java.util.function.Function<WebTreeNode<WebReferenceResolveItem>, WebTreeNode<WebReferenceResolveItem>> tree =
                new java.util.function.Function<>() {
                    @Override public WebTreeNode<WebReferenceResolveItem> apply(
                            WebTreeNode<WebReferenceResolveItem> node) {
                        return new WebTreeNode<>(display.apply(node.record()), node.children().stream().map(this).toList());
                    }
                };
        return new WebReferenceResolveResponse(response.status(), response.mode(),
                response.options().stream().map(display).toList(), response.results().stream().map(result ->
                new WebReferenceResolveResult(result.input(), result.status(), result.matchedBy(),
                        display.apply(result.item()), result.candidates().stream().map(display).toList())).toList(),
                response.offset(), response.limit(), response.total(), response.tree().stream().map(tree).toList());
    }

    @SuppressWarnings("unchecked")
    private static <T extends EntityContract> Map<String, Map<String, TextReference>> sources(RecordTextSourceAbility<T> ability, Set<String> ids, RecordReadVisibility visibility) {
        Map<String, Map<String, TextReference>> result = new LinkedHashMap<>();
        // One scoped source read inside the producer's read boundary, with the same retained/active range.
        var criteria = Criteria.of().in("id", List.copyOf(ids));
        var page = new PageRequest(0, ids.size());
        List<T> records = visibility == RecordReadVisibility.RETAINED
                ? ((RecycleBinAbility<T>) ability).pageRecycleBin(criteria, page).getRecords()
                : ability.list(criteria, page);
        for (T record : records)
            result.put(record.getId(), new LinkedHashMap<>(ability.recordTextSources(record)));
        return result;
    }

    private static void collectRecords(JsonNode node, List<ObjectNode> output) {
        if (node == null) return;
        if (node.isArray()) { node.forEach(item -> collectRecords(item, output)); return; }
        if (!(node instanceof ObjectNode object)) return;
        if (object.path("id").isTextual() && !object.path("id").asText().isBlank()) { output.add(object); return; }
        // Only standard record envelopes; arbitrary nested business objects are not presentation documents.
        collectRecords(object.get("records"), output);
        collectRecords(object.get("record"), output);
        collectRecords(object.get("children"), output);
    }
}
