package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.reference.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.title.TitleFieldResolver;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.platform.content.BusinessContentQueryService;
import net.ximatai.muyun.spring.platform.exchange.RecordExchangeAccess;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.web.*;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * File names use the ordinary candidate boundary. Matching a machine label only offers an object
 * choice.
 */
@Component
public final class RecordExchangeReferenceSupport {
    private final StaticAbilityCatalog statics;
    private final DynamicRecordService records;
    private final BusinessContentQueryService queries;
    private final BusinessContentRecordPresentationService presentations;

    public RecordExchangeReferenceSupport(
            StaticAbilityCatalog statics,
            DynamicRecordService records,
            BusinessContentQueryService queries,
            BusinessContentRecordPresentationService presentations) {
        this.statics = Objects.requireNonNull(statics);
        this.records = Objects.requireNonNull(records);
        this.queries = Objects.requireNonNull(queries);
        this.presentations = Objects.requireNonNull(presentations);
    }

    public record Source(
            String module,
            String entity,
            ReferencePlan plan,
            boolean dynamic,
            DynamicReferenceDescriptor descriptor) {}

    public RecordExchangeAccess.Reference resolve(
            Source source, String input, String language, Map<String, Object> values) {
        if (source.plan().cardinality() == ReferenceCardinality.MANY) {
            var tokens =
                    Arrays.stream(input.split(","))
                            .map(String::trim)
                            .filter(value -> !value.isBlank())
                            .distinct()
                            .toList();
            if (tokens.size() > 20)
                throw new IllegalArgumentException(
                        "Select at most 20 objects in one reference cell");
            List<RecordExchangeAccess.Candidate> combined =
                    List.of(new RecordExchangeAccess.Candidate("", "", "", "ORIGINAL"));
            for (String token : tokens) {
                var matching = resolveOne(source, token, language, values).candidates();
                if (matching.isEmpty()) return new RecordExchangeAccess.Reference(List.of());
                var next = new ArrayList<RecordExchangeAccess.Candidate>();
                for (var left : combined)
                    for (var right : matching) {
                        String ids =
                                String.join(
                                        ",",
                                        java.util.stream.Stream.concat(
                                                        source
                                                                .plan()
                                                                .normalizeValues(left.id())
                                                                .stream(),
                                                        java.util.stream.Stream.of(right.id()))
                                                .distinct()
                                                .toList());
                        next.add(
                                new RecordExchangeAccess.Candidate(
                                        ids,
                                        left.title().isBlank()
                                                ? right.title()
                                                : left.title() + ", " + right.title(),
                                        input,
                                        "MACHINE".equals(left.kind())
                                                        || "MACHINE".equals(right.kind())
                                                ? "MACHINE"
                                                : "CONFIRMED"));
                        if (next.size() > 20)
                            throw new IllegalArgumentException(
                                    "Reference combinations are ambiguous; provide codes or"
                                        + " narrower dependencies");
                    }
                combined = next;
            }
            return new RecordExchangeAccess.Reference(combined);
        }
        return resolveOne(source, input, language, values);
    }

    private RecordExchangeAccess.Reference resolveOne(
            Source source, String input, String language, Map<String, Object> values) {
        var owner = owner(source.plan().target());
        String label = label(source, owner);
        if (target(source.plan().target()).isReferenceFieldProtected(label))
            throw new PlatformAccessDeniedException(
                    "Protected fields cannot resolve exchange names");
        var candidates = new LinkedHashMap<String, RecordExchangeAccess.Candidate>();
        for (String kind : List.of("ORIGINAL", "CONFIRMED", "MACHINE")) {
            Criteria criteria = queries.referenceName(owner, label, input, language, kind);
            if ("ORIGINAL".equals(kind) && source.plan().targetKeyField() != null)
                criteria.orGroup(Criteria.of().eq(source.plan().targetKeyField(), input).getRoot());
            for (var item : candidates(source, criteria, values))
                candidates.putIfAbsent(
                        item.id(),
                        new RecordExchangeAccess.Candidate(item.id(), item.title(), input, kind));
        }
        if (candidates.size() > 20)
            throw new IllegalArgumentException("引用重名过多，请填写编码或补充限定条件 / Narrow the reference name");
        return new RecordExchangeAccess.Reference(List.copyOf(candidates.values()));
    }

    public String title(Source source, Object ids, String language, Map<String, Object> values) {
        if (ids == null) return null;
        var input = source.plan().normalizeValues(ids);
        var projected = presentations.projectReference(source.plan().target(), label(source, owner(source.plan().target())), null, language, () -> {
            var items = byIds(source, input, values);
            return new WebReferenceResolveResponse(WebReferenceResolveStatus.OK, WebReferenceResolveMode.QUERY,
                    items.stream().map(item -> new WebReferenceResolveItem(item.id(), item.title(), null, Map.of(), Map.of())).toList(),
                    List.of(), 0, items.size(), items.size());
        });
        var titles = new LinkedHashMap<String, String>();
        for (var item : projected.options())
            titles.put(
                    item.id(),
                    item.titleContent() == null || language == null
                            ? item.title()
                            : item.titleContent().readings().getOrDefault(language, item.title()));
        if (!titles.keySet().containsAll(input))
            throw new IllegalArgumentException("引用对象当前不可访问，请修正 / Reference is unavailable");
        return String.join(",", input.stream().map(titles::get).toList());
    }

    public void require(Source source, Object ids, Map<String, Object> values) {
        if (ids == null) return;
        var input = source.plan().normalizeValues(ids);
        var resolved =
                byIds(source, input, values).stream()
                        .map(Item::id)
                        .collect(java.util.stream.Collectors.toSet());
        if (!resolved.containsAll(input))
            throw new IllegalArgumentException("引用对象当前不可访问，请修正 / Reference is unavailable");
    }

    private record Item(String id, String title) {}

    private List<Item> byIds(Source source, List<String> ids, Map<String, Object> values) {
        var result = new ArrayList<Item>();
        for (int offset = 0; offset < ids.size(); offset += 20)
            result.addAll(
                    candidates(
                            source,
                            Criteria.of()
                                    .in(
                                            "id",
                                            ids.subList(offset, Math.min(offset + 20, ids.size()))),
                            values));
        return result;
    }

    private List<Item> candidates(Source source, Criteria criteria, Map<String, Object> values) {
        if (source.dynamic()) {
            var response =
                    records.resolveReference(
                            source.module(),
                            source.entity(),
                            source.plan().sourceField(),
                            new DynamicReferenceResolveRequest(
                                    DynamicReferenceResolveMode.QUERY,
                                    DynamicReferenceMatchMode.LABEL,
                                    null,
                                    List.of(),
                                    criteria,
                                    PageRequest.of(1, 21),
                                    false,
                                    values));
            if (response.total() > 20)
                throw new IllegalArgumentException(
                        "Too many reference candidates; provide a code or dependency");
            return response.options().stream()
                    .map(item -> new Item(item.id(), item.title()))
                    .toList();
        }
        var ability = target(source.plan().target());
        String label = label(source, owner(source.plan().target()));
        if (ability.isReferenceFieldProtected(label))
            throw new PlatformAccessDeniedException(
                    "Protected fields cannot resolve exchange names");
        Criteria scoped =
                ReferenceCandidateCriteria.from(source.plan().candidateDependencies(), values)
                        .and(criteria);
        var result = ability.referenceOptions(source.plan(), scoped, PageRequest.of(1, 21));
        if (result.getTotal() > 20)
            throw new IllegalArgumentException(
                    "Too many reference candidates; provide a code or dependency");
        return result.getRecords().stream().map(item -> new Item(item.id(), item.title())).toList();
    }

    private CrudAbility<?> owner(ReferenceTarget target) {
        return statics.findByTarget(target)
                .orElseGet(() -> records.entity(target.moduleAlias(), target.entityAlias()));
    }

    private ReferenceAbility<?> target(ReferenceTarget target) {
        return statics.findReference(target)
                .or(() -> records.referenceAbility(target))
                .orElseThrow(() -> new IllegalArgumentException("Reference target is unavailable"));
    }

    private static String label(Source source, CrudAbility<?> owner) {
        if (source.plan().targetLabelField() != null) return source.plan().targetLabelField();
        if (owner.getDao() instanceof DynamicRecordDao dao)
            return dao.getEntity().fields().stream()
                    .filter(field -> field.isTitle())
                    .map(net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition::fieldName)
                    .findFirst()
                    .orElse("title");
        return TitleFieldResolver.resolveFieldName(owner.modelClass()).orElse("title");
    }
}
