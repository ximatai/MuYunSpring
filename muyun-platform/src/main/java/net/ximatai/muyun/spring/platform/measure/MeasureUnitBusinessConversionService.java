package net.ximatai.muyun.spring.platform.measure;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MeasureUnitBusinessConversionService {
    private final MeasureUnitService unitService;
    private final MeasureUnitConversionRuleService ruleService;

    public MeasureUnitBusinessConversionService(MeasureUnitService unitService,
                                                MeasureUnitConversionRuleService ruleService) {
        this.unitService = unitService;
        this.ruleService = ruleService;
    }

    public MeasureUnitBusinessConversion convert(MeasureUnitConversionContext context,
                                                 BigDecimal value,
                                                 String fromCategoryAlias,
                                                 String fromUnitCode,
                                                 String toCategoryAlias,
                                                 String toUnitCode) {
        if (value == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-business-conversion.measure-business-conversion-value-must-not-be-null",
                    "measure business conversion value must not be null"));
        }
        MeasureUnitConversionContext validContext = normalizeContext(context);
        UnitKey source = unitKey(validContext.applicationAlias(), fromCategoryAlias, fromUnitCode, true);
        UnitKey target = unitKey(validContext.applicationAlias(), toCategoryAlias, toUnitCode, true);
        if (source.equals(target)) {
            return new MeasureUnitBusinessConversion(validContext, value, source.categoryAlias(), source.unitCode(),
                    target.categoryAlias(), target.unitCode(), value, List.of());
        }
        Map<UnitKey, List<Edge>> graph = graph(validContext);
        Path path = findBestPath(source, target, graph);
        if (path == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-business-conversion.measure-business-conversion-rule-not-found",
                    "measure business conversion rule not found: {value1} -> {value2}"), Map.of("value1", String.valueOf(source.label()), "value2", String.valueOf(target.label())));
        }
        BigDecimal converted = value;
        for (Edge edge : path.edges()) {
            converted = converted.multiply(edge.factor(), MathContext.DECIMAL128);
        }
        return new MeasureUnitBusinessConversion(validContext, value, source.categoryAlias(), source.unitCode(),
                target.categoryAlias(), target.unitCode(), converted, path.edges().stream().map(Edge::ruleId).toList());
    }

    private Map<UnitKey, List<Edge>> graph(MeasureUnitConversionContext context) {
        Map<EdgeKey, Edge> bestEdges = new LinkedHashMap<>();
        for (MeasureUnitConversionRule rule : ruleService.applicableRules(context)) {
            UnitKey from = unitKey(rule.getApplicationAlias(), rule.getFromCategoryAlias(), rule.getFromUnitCode(), true);
            UnitKey to = unitKey(rule.getApplicationAlias(), rule.getToCategoryAlias(), rule.getToUnitCode(), true);
            addBest(bestEdges, new Edge(from, to, rule.getFactor(), rule.getId(),
                    specificity(rule), priority(rule), visibilityPriority(context, rule)));
            addBest(bestEdges, new Edge(to, from,
                    BigDecimal.ONE.divide(rule.getFactor(), MathContext.DECIMAL128),
                    rule.getId(), specificity(rule), priority(rule), visibilityPriority(context, rule)));
        }
        Map<UnitKey, List<Edge>> graph = new LinkedHashMap<>();
        for (Edge edge : bestEdges.values()) {
            graph.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        }
        for (List<Edge> edges : graph.values()) {
            edges.sort(Comparator.comparingInt(Edge::specificity).reversed()
                    .thenComparing(Comparator.comparingInt(Edge::priority).reversed())
                    .thenComparing(Comparator.comparingInt(Edge::visibilityPriority).reversed()));
        }
        return graph;
    }

    private void addBest(Map<EdgeKey, Edge> edges, Edge candidate) {
        EdgeKey key = new EdgeKey(candidate.from(), candidate.to());
        Edge existing = edges.get(key);
        if (existing == null || compareEdge(candidate, existing) > 0) {
            edges.put(key, candidate);
        }
    }

    private int compareEdge(Edge left, Edge right) {
        int specificity = Integer.compare(left.specificity(), right.specificity());
        if (specificity != 0) {
            return specificity;
        }
        int priority = Integer.compare(left.priority(), right.priority());
        if (priority != 0) {
            return priority;
        }
        return Integer.compare(left.visibilityPriority(), right.visibilityPriority());
    }

    private Path findBestPath(UnitKey source, UnitKey target, Map<UnitKey, List<Edge>> graph) {
        return findBestPath(source, target, graph, new LinkedHashSet<>(), List.of(), null);
    }

    private Path findBestPath(UnitKey current,
                              UnitKey target,
                              Map<UnitKey, List<Edge>> graph,
                              Set<UnitKey> visited,
                              List<Edge> currentEdges,
                              Path best) {
        visited.add(current);
        for (Edge edge : graph.getOrDefault(current, List.of())) {
            if (visited.contains(edge.to())) {
                continue;
            }
            List<Edge> nextEdges = new ArrayList<>(currentEdges);
            nextEdges.add(edge);
            if (edge.to().equals(target)) {
                Path candidate = new Path(edge.to(), List.copyOf(nextEdges));
                if (best == null || comparePath(candidate, best) > 0) {
                    best = candidate;
                }
                continue;
            }
            best = findBestPath(edge.to(), target, graph, visited, List.copyOf(nextEdges), best);
            visited.remove(edge.to());
        }
        visited.remove(current);
        return best;
    }

    private int comparePath(Path left, Path right) {
        int recordContext = Integer.compare(countSpecificity(left, 2), countSpecificity(right, 2));
        if (recordContext != 0) {
            return recordContext;
        }
        int module = Integer.compare(countSpecificity(left, 1), countSpecificity(right, 1));
        if (module != 0) {
            return module;
        }
        int priority = Integer.compare(totalPriority(left), totalPriority(right));
        if (priority != 0) {
            return priority;
        }
        int visibility = Integer.compare(totalVisibilityPriority(left), totalVisibilityPriority(right));
        if (visibility != 0) {
            return visibility;
        }
        return Integer.compare(right.edges().size(), left.edges().size());
    }

    private int countSpecificity(Path path, int specificity) {
        return (int) path.edges().stream().filter(edge -> edge.specificity() == specificity).count();
    }

    private int totalPriority(Path path) {
        return path.edges().stream().mapToInt(Edge::priority).sum();
    }

    private int totalVisibilityPriority(Path path) {
        return path.edges().stream().mapToInt(Edge::visibilityPriority).sum();
    }

    private UnitKey unitKey(String applicationAlias, String categoryAlias, String unitCode, boolean requireEnabled) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = PlatformNameRules.requireIdentifier(categoryAlias, "measureUnitCategoryAlias");
        String validUnitCode = PlatformNameRules.requireCode(unitCode, "measureUnitCode");
        if (requireEnabled) {
            unitService.requireEnabledVisibleUnit(validApplicationAlias, validCategoryAlias, validUnitCode);
        }
        return new UnitKey(validCategoryAlias, validUnitCode);
    }

    private MeasureUnitConversionContext normalizeContext(MeasureUnitConversionContext context) {
        if (context == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.measure-unit-business-conversion.measure-conversion-context-must-not-be-null",
                    "measure conversion context must not be null"));
        }
        String applicationAlias = PlatformNameRules.requireApplicationAlias(context.applicationAlias());
        String moduleAlias = context.moduleAlias() == null || context.moduleAlias().isBlank()
                ? null
                : PlatformNameRules.requireModuleAliasInApplication(context.moduleAlias(), applicationAlias);
        String contextObjectType = context.contextObjectType() == null || context.contextObjectType().isBlank()
                ? null
                : PlatformNameRules.requireCode(context.contextObjectType(), "contextObjectType");
        String contextObjectId = context.contextObjectId() == null || context.contextObjectId().isBlank()
                ? null
                : context.contextObjectId();
        return new MeasureUnitConversionContext(applicationAlias, moduleAlias,
                contextObjectType, contextObjectId, context.operatedAt());
    }

    private int specificity(MeasureUnitConversionRule rule) {
        return switch (rule.getScopeType()) {
            case GLOBAL -> 0;
            case MODULE -> 1;
            case RECORD_CONTEXT -> 2;
        };
    }

    private int priority(MeasureUnitConversionRule rule) {
        return rule.getPriority() == null ? 0 : rule.getPriority();
    }

    private int visibilityPriority(MeasureUnitConversionContext context, MeasureUnitConversionRule rule) {
        if (MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS.equals(rule.getApplicationAlias())) {
            return 2;
        }
        if (Objects.equals(context.applicationAlias(), rule.getApplicationAlias())) {
            return 1;
        }
        return 0;
    }

    private record UnitKey(String categoryAlias, String unitCode) {
        String label() {
            return categoryAlias + "." + unitCode;
        }
    }

    private record EdgeKey(UnitKey from, UnitKey to) {
    }

    private record Edge(UnitKey from,
                        UnitKey to,
                        BigDecimal factor,
                        String ruleId,
                        int specificity,
                        int priority,
                        int visibilityPriority) {
    }

    private record Path(UnitKey current, List<Edge> edges) {
        private Path {
            edges = List.copyOf(Objects.requireNonNull(edges));
        }
    }
}
