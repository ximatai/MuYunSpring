package net.ximatai.muyun.spring.platform.ui;

import java.util.List;

/**
 * The target LIST projection issued with an executable relation query contract.
 */
public record ResolvedDetailRelationListProjection(
        String uiConfigId,
        List<ResolvedDetailRelationListField> fields
) {
    public ResolvedDetailRelationListProjection {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
