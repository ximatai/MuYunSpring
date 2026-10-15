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
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** Projects only producer-owned labels already present in authorized read records. */
@Component
public class RecordTextPresentationService {
    private final ObjectMapper mapper;
    private final ObjectProvider<StaticAbilityCatalog> abilities;
    private final ObjectProvider<TextResolver> resolvers;
    private final ObjectProvider<LanguageCatalog> catalogs;
    private final ObjectProvider<OptionSourceRegistry> options;
    public RecordTextPresentationService(ObjectMapper mapper, ObjectProvider<StaticAbilityCatalog> abilities,
            ObjectProvider<TextResolver> resolvers, ObjectProvider<LanguageCatalog> catalogs, ObjectProvider<OptionSourceRegistry> options) {
        this.mapper = mapper; this.abilities = abilities; this.resolvers = resolvers; this.catalogs = catalogs; this.options = options;
    }
    @SuppressWarnings("unchecked")
    public Object project(String moduleAlias, Object body) {
        var registry = abilities.getIfAvailable(); var resolver = resolvers.getIfAvailable(); var catalog = catalogs.getIfAvailable();
        if (body == null || registry == null || resolver == null || catalog == null) return body;
        var ability = registry.abilities().stream().filter(service -> moduleAlias.equals(service.getModuleAlias()))
                .findFirst().orElse(null);
        if (ability == null || (!(ability instanceof RecordTextSourceAbility<?>) && OptionLoadResolver.resolve(ability.modelClass()).isEmpty())) return body;
        JsonNode output = mapper.valueToTree(body);
        List<ObjectNode> records = new ArrayList<>(); collectRecords(output, records);
        Set<String> ids = new LinkedHashSet<>(); records.forEach(record -> ids.add(record.path("id").asText()));
        if (ids.isEmpty()) return body;
        Map<String, Map<String, TextReference>> sources = ability instanceof RecordTextSourceAbility<?> sourceAbility
                ? sources((RecordTextSourceAbility<EntityContract>) sourceAbility, ids) : new LinkedHashMap<>();
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
        var prepared = resolver.prepare(records.stream().flatMap(record -> sources.getOrDefault(record.path("id").asText(), Map.of()).entrySet().stream()
                .filter(entry -> record.path(entry.getKey()).isTextual() && record.path(entry.getKey()).asText().equals(entry.getValue().originalText()))
                .map(Map.Entry::getValue)).distinct().toList());
        for (var record : records) {
            var fields = sources.getOrDefault(record.path("id").asText(), Map.of());
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

    public net.ximatai.muyun.spring.web.WebReferenceResolveResponse projectReference(String moduleAlias, String labelField,
            net.ximatai.muyun.spring.web.WebReferenceResolveResponse response) {
        if ("id".equals(labelField)) return response;
        var items = new ArrayList<net.ximatai.muyun.spring.web.WebReferenceResolveItem>();
        items.addAll(response.options());
        for (var result : response.results()) {
            if (result.item() != null) items.add(result.item());
            items.addAll(result.candidates());
        }
        java.util.function.Consumer<net.ximatai.muyun.spring.web.WebTreeNode<net.ximatai.muyun.spring.web.WebReferenceResolveItem>> collect =
                new java.util.function.Consumer<>() {
                    @Override public void accept(net.ximatai.muyun.spring.web.WebTreeNode<net.ximatai.muyun.spring.web.WebReferenceResolveItem> node) {
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
        java.util.function.UnaryOperator<net.ximatai.muyun.spring.web.WebReferenceResolveItem> display = item -> {
            if (item == null) return null;
            var text = texts.get(item.id());
            return text != null && text.source().originalText().equals(item.title()) ? item.withTitleText(text) : item;
        };
        java.util.function.Function<net.ximatai.muyun.spring.web.WebTreeNode<net.ximatai.muyun.spring.web.WebReferenceResolveItem>, net.ximatai.muyun.spring.web.WebTreeNode<net.ximatai.muyun.spring.web.WebReferenceResolveItem>> tree =
                new java.util.function.Function<>() {
                    @Override public net.ximatai.muyun.spring.web.WebTreeNode<net.ximatai.muyun.spring.web.WebReferenceResolveItem> apply(
                            net.ximatai.muyun.spring.web.WebTreeNode<net.ximatai.muyun.spring.web.WebReferenceResolveItem> node) {
                        return new net.ximatai.muyun.spring.web.WebTreeNode<>(display.apply(node.record()), node.children().stream().map(this).toList());
                    }
                };
        return new net.ximatai.muyun.spring.web.WebReferenceResolveResponse(response.status(), response.mode(),
                response.options().stream().map(display).toList(), response.results().stream().map(result ->
                new net.ximatai.muyun.spring.web.WebReferenceResolveResult(result.input(), result.status(), result.matchedBy(),
                        display.apply(result.item()), result.candidates().stream().map(display).toList())).toList(),
                response.offset(), response.limit(), response.total(), response.tree().stream().map(tree).toList());
    }

    @SuppressWarnings("unchecked")
    private static <T extends EntityContract> Map<String, Map<String, TextReference>> sources(RecordTextSourceAbility<T> ability, Set<String> ids) {
        Map<String, Map<String, TextReference>> result = new LinkedHashMap<>();
        // One normal scoped read per response, independent of record/field count. No privileged source-maintenance bypass.
        for (T record : ability.list(Criteria.of().in("id", List.copyOf(ids)), new PageRequest(0, ids.size())))
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
