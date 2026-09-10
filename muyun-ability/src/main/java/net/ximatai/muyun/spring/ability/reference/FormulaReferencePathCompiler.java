package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition;
import net.ximatai.muyun.spring.common.formula.FormulaFieldPath;
import net.ximatai.muyun.spring.common.formula.FormulaValueType;

import java.util.ArrayList;
import java.util.List;

/** Compiles the small, read-only reference surface accepted by save-time formulas. */
public final class FormulaReferencePathCompiler {
    public static final int MAX_HOPS = ReferencePathPlanningOptions.DEFAULT_MAX_HOPS;

    private FormulaReferencePathCompiler() {
    }

    public static CompiledPath compile(ReferenceTarget root, String value, ReferenceTargetResolver resolver) {
        if (root == null || value == null || value.isBlank() || !value.contains(".")) {
            throw new IllegalArgumentException("formula reference field must be a dotted path: " + value);
        }
        String path = value.trim();
        String[] segments = path.split("\\.", -1);
        if (segments.length < 2 || segments.length - 1 > MAX_HOPS) {
            throw new IllegalArgumentException("formula reference path exceeds " + MAX_HOPS + " reference hops: " + path);
        }
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("formula reference path contains a blank segment: " + path);
            }
        }
        ReferenceTarget current = root;
        ReferencePlan first = plan(current, segments[0], resolver);
        requireOne(first, path);
        requireFormulaReadable(current, segments[0], path, resolver);
        if (!"id".equals(first.targetKeyField())) {
            throw new IllegalArgumentException("formula reference path requires id-backed reference: " + path);
        }
        current = first.target();
        List<ReferenceLoadPath.Hop> hops = new ArrayList<>();
        for (int index = 1; index < segments.length - 1; index++) {
            ReferencePlan hop = plan(current, segments[index], resolver);
            requireOne(hop, path);
            requireFormulaReadable(current, segments[index], path, resolver);
            if (!"id".equals(hop.targetKeyField())) {
                throw new IllegalArgumentException("formula reference path requires id-backed reference: " + path);
            }
            hops.add(new ReferenceLoadPath.Hop(hop.target(), hop.sourceField()));
            current = hop.target();
        }
        FormulaValueType terminalType = resolver.formulaFieldType(current, segments[segments.length - 1])
                .orElseThrow(() -> new IllegalArgumentException(
                        "formula reference terminal field does not exist or is protected: " + path));
        if (terminalType == FormulaValueType.ANY || terminalType == FormulaValueType.JSON) {
            throw new IllegalArgumentException("formula reference terminal field must be a scalar: " + path);
        }
        return new CompiledPath(path, new ReferenceLoadPath(first.sourceField(), first.target(), hops,
                segments[segments.length - 1], path), new FormulaFieldDefinition(FormulaFieldPath.parse(path),
                terminalType, false, false));
    }

    private static ReferencePlan plan(ReferenceTarget source, String field, ReferenceTargetResolver resolver) {
        return resolver.referencePlan(source, field).orElseThrow(() -> new IllegalArgumentException(
                "formula reference path is not a declared reference: " + source.qualifiedName() + "." + field));
    }

    private static void requireOne(ReferencePlan plan, String path) {
        if (plan.cardinality() != ReferenceCardinality.ONE) {
            throw new IllegalArgumentException("formula reference path requires ONE cardinality: " + path);
        }
    }

    private static void requireFormulaReadable(ReferenceTarget source, String field, String path,
                                               ReferenceTargetResolver resolver) {
        if (resolver.formulaFieldType(source, field).isEmpty()) {
            throw new IllegalArgumentException("formula reference field does not exist or is protected: " + path);
        }
    }

    public record CompiledPath(String path, ReferenceLoadPath loadPath, FormulaFieldDefinition field) {
    }
}
