package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.ability.query.QuerySchema;

/**
 * A server-issued query contract for a detail relation.  Its absence is intentional: a relation
 * declaration alone must never cause a client to invent a read endpoint.
 */
public record ResolvedDetailRelationQueryContract(
        String queryPath,
        String targetUiConfigId,
        String queryTemplateId,
        boolean pageable,
        boolean queryable,
        ResolvedDetailRelationListProjection listProjection,
        QuerySchema querySchema
) {
    /** Source-compatible constructor for contracts issued before relation query schemas were explicit. */
    public ResolvedDetailRelationQueryContract(String queryPath,
                                               String targetUiConfigId,
                                               String queryTemplateId,
                                               boolean pageable,
                                               boolean queryable,
                                               ResolvedDetailRelationListProjection listProjection) {
        this(queryPath, targetUiConfigId, queryTemplateId, pageable, queryable, listProjection, null);
    }

    /** Source-compatible constructor for contracts issued before list projection was explicit. */
    public ResolvedDetailRelationQueryContract(String queryPath,
                                               String targetUiConfigId,
                                               String queryTemplateId,
                                               boolean pageable,
                                               boolean queryable) {
        this(queryPath, targetUiConfigId, queryTemplateId, pageable, queryable, null, null);
    }

    public ResolvedDetailRelationQueryContract {
        queryPath = requireText(queryPath, "detail relation query path");
        targetUiConfigId = normalize(targetUiConfigId);
        queryTemplateId = normalize(queryTemplateId);
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
