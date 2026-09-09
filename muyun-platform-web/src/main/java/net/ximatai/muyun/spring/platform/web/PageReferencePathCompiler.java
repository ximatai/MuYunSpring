package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.PlatformAuditReferences;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadPath;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;

import java.util.ArrayList;
import java.util.List;

/** Compiles only proven ONE-reference page paths; it deliberately has no query capability. */
public final class PageReferencePathCompiler {
    public static final int MAX_HOPS = RelationProjectionPlanningOptions.DEFAULT_MAX_JOIN_DEPTH;

    private PageReferencePathCompiler() {
    }

    /**
     * The root target must come from the module definition for dynamic modules: their main
     * entity alias is independent of the last segment of the module alias.
     */
    public static PageReferencePath compile(ReferenceTarget rootTarget, String value) {
        if (value == null || value.isBlank() || !value.contains(".")) {
            throw new IllegalArgumentException("page reference field must be a dotted path: " + value);
        }
        String path = value.trim();
        String[] segments = path.split("\\.", -1);
        if (segments.length < 2 || segments.length - 1 > MAX_HOPS) {
            throw new IllegalArgumentException("page reference path exceeds " + MAX_HOPS + " reference hops: " + path);
        }
        if (rootTarget == null) {
            throw new IllegalArgumentException("page reference path requires a source target");
        }
        ReferenceTarget current = rootTarget;
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("page reference path contains a blank segment: " + path);
            }
        }
        ReferencePlan first = plan(current, segments[0]);
        requireOne(first, path);
        current = first.target();
        List<ReferenceLoadPath.Hop> hops = new ArrayList<>();
        for (int index = 1; index < segments.length - 1; index++) {
            ReferencePlan hop = plan(current, segments[index]);
            requireOne(hop, path);
            hops.add(new ReferenceLoadPath.Hop(hop.target(), hop.sourceField()));
            current = hop.target();
        }
        return new PageReferencePath(path, new ReferenceLoadPath(first.sourceField(), first.target(), hops,
                segments[segments.length - 1], path));
    }

    public static ReferencePlan plan(ReferenceTarget source, String field) {
        if (source == null || field == null || field.isBlank()) {
            throw new IllegalArgumentException("page reference path contains a blank field");
        }
        var audit = PlatformAuditReferences.plans().stream()
                .filter(candidate -> field.equals(candidate.sourceField())).findFirst();
        if (audit.isPresent()) return audit.get();
        return PlatformAbilityRuntime.referenceTargetResolver().referencePlan(source, field)
                .orElseThrow(() -> new IllegalArgumentException("page reference path is not a declared reference: "
                        + source.qualifiedName() + "." + field));
    }

    private static void requireOne(ReferencePlan plan, String path) {
        if (plan.cardinality() != ReferenceCardinality.ONE) {
            throw new IllegalArgumentException("page reference path requires ONE cardinality: " + path);
        }
    }
}
