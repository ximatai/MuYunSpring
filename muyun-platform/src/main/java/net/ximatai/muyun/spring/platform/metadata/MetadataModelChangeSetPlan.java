package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Immutable full-tree write plan produced by preview and consumed by the one model publisher. */
public record MetadataModelChangeSetPlan(
        List<MetadataModelRelationPlan> relationPlans,
        List<MetadataModelRelationOrderPlan> relationOrderPlans,
        List<MetadataModelFieldOrderPlan> fieldOrderPlans
) {
}
