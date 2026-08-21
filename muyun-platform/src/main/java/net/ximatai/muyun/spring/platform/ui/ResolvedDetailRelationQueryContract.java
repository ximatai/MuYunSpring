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
        String actionCode,
        Integer pageSize,
        java.util.List<Integer> pageSizeOptions
) {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final java.util.List<Integer> DEFAULT_PAGE_SIZE_OPTIONS = java.util.List.of(10, 20, 50);

    /** Source-compatible canonical constructor for contracts before paging parameters were explicit. */
    public ResolvedDetailRelationQueryContract(String queryPath,
                                               String targetUiConfigId,
                                               String queryTemplateId,
                                               boolean pageable,
                                               boolean queryable,
                                               ResolvedDetailRelationListProjection listProjection,
                                               QuerySchema querySchema,
                                               boolean managedGateway,
                                               String actionCode) {
        this(queryPath, targetUiConfigId, queryTemplateId, pageable, queryable, listProjection, querySchema,
                managedGateway, actionCode, pageable ? DEFAULT_PAGE_SIZE : null,
                pageable ? DEFAULT_PAGE_SIZE_OPTIONS : java.util.List.of());
    }
    /** Source-compatible constructor for contracts issued before relation query schemas were explicit. */
    public ResolvedDetailRelationQueryContract(String queryPath,
                                               String targetUiConfigId,
                                               String queryTemplateId,
                                               boolean pageable,
                                               boolean queryable,
                                               ResolvedDetailRelationListProjection listProjection) {
        this(queryPath, targetUiConfigId, queryTemplateId, pageable, queryable, listProjection, null, false, null,
                pageable ? DEFAULT_PAGE_SIZE : null, pageable ? DEFAULT_PAGE_SIZE_OPTIONS : java.util.List.of());
    }

    /** Source-compatible constructor for relation contracts that already include a query schema. */
    public ResolvedDetailRelationQueryContract(String queryPath,
                                               String targetUiConfigId,
                                               String queryTemplateId,
                                               boolean pageable,
                                               boolean queryable,
                                               ResolvedDetailRelationListProjection listProjection,
                                               QuerySchema querySchema) {
        this(queryPath, targetUiConfigId, queryTemplateId, pageable, queryable, listProjection, querySchema, false, null,
                pageable ? DEFAULT_PAGE_SIZE : null, pageable ? DEFAULT_PAGE_SIZE_OPTIONS : java.util.List.of());
    }

    /** Source-compatible constructor for contracts issued before list projection was explicit. */
    public ResolvedDetailRelationQueryContract(String queryPath,
                                               String targetUiConfigId,
                                               String queryTemplateId,
                                               boolean pageable,
                                               boolean queryable) {
        this(queryPath, targetUiConfigId, queryTemplateId, pageable, queryable, null, null, false, null,
                pageable ? DEFAULT_PAGE_SIZE : null, pageable ? DEFAULT_PAGE_SIZE_OPTIONS : java.util.List.of());
    }

    /** Managed gateway constructor with the compiled parent-module action code. */
    public ResolvedDetailRelationQueryContract(String targetUiConfigId, boolean pageable, boolean queryable,
                                               ResolvedDetailRelationListProjection listProjection,
                                               QuerySchema querySchema, String actionCode) {
        this(null, targetUiConfigId, null, pageable, queryable, listProjection, querySchema, true, actionCode,
                pageable ? DEFAULT_PAGE_SIZE : null, pageable ? DEFAULT_PAGE_SIZE_OPTIONS : java.util.List.of());
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
        pageSizeOptions = pageSizeOptions == null ? java.util.List.of() : java.util.List.copyOf(pageSizeOptions);
        if (pageSizeOptions.stream().anyMatch(value -> value <= 0 || value > 500)) {
            throw new IllegalArgumentException("detail relation page size options must be between 1 and 500");
        }
        pageSizeOptions = pageSizeOptions.stream().distinct().sorted().toList();
        if (pageable && (pageSize == null || pageSize <= 0 || pageSize > 500
                || pageSizeOptions.isEmpty() || !pageSizeOptions.contains(pageSize))) {
            throw new IllegalArgumentException("pageable detail relation must include its default page size in options");
        }
        if (!pageable && (pageSize != null || !pageSizeOptions.isEmpty())) {
            throw new IllegalArgumentException("unpaged detail relation must not expose paging parameters");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
