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
        QuerySchema querySchema,
        boolean managedGateway,
        String actionCode
) {
    /** Source-compatible constructor for contracts issued before relation query schemas were explicit. */
    public ResolvedDetailRelationQueryContract(String queryPath,
                                               String targetUiConfigId,
                                               String queryTemplateId,
                                               boolean pageable,
                                               boolean queryable,
                                               ResolvedDetailRelationListProjection listProjection) {
        this(queryPath, targetUiConfigId, queryTemplateId, pageable, queryable, listProjection, null, false, null);
    }

    /** Source-compatible constructor for relation contracts that already include a query schema. */
    public ResolvedDetailRelationQueryContract(String queryPath,
                                               String targetUiConfigId,
                                               String queryTemplateId,
                                               boolean pageable,
                                               boolean queryable,
                                               ResolvedDetailRelationListProjection listProjection,
                                               QuerySchema querySchema) {
        this(queryPath, targetUiConfigId, queryTemplateId, pageable, queryable, listProjection, querySchema, false, null);
    }

    /** Source-compatible constructor for contracts issued before list projection was explicit. */
    public ResolvedDetailRelationQueryContract(String queryPath,
                                               String targetUiConfigId,
                                               String queryTemplateId,
                                               boolean pageable,
                                               boolean queryable) {
        this(queryPath, targetUiConfigId, queryTemplateId, pageable, queryable, null, null, false, null);
    }

    /** Managed gateway constructor with the compiled parent-module action code. */
    public ResolvedDetailRelationQueryContract(String targetUiConfigId, boolean pageable, boolean queryable,
                                               ResolvedDetailRelationListProjection listProjection,
                                               QuerySchema querySchema, String actionCode) {
        this(null, targetUiConfigId, null, pageable, queryable, listProjection, querySchema, true, actionCode);
    }

    public ResolvedDetailRelationQueryContract {
        queryPath = normalize(queryPath);
        if (!managedGateway && queryPath == null) {
            throw new IllegalArgumentException("detail relation query path must not be blank");
        }
        targetUiConfigId = normalize(targetUiConfigId);
        queryTemplateId = normalize(queryTemplateId);
        actionCode = normalize(actionCode);
        if (managedGateway && actionCode == null) {
            throw new IllegalArgumentException("managed detail relation query action code must not be blank");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
