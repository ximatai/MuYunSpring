package net.ximatai.muyun.spring.platform.web;

public enum ProjectionQueryFallbackReason {
    NONE,
    MISSING_EXECUTOR,
    MISSING_DEFINITION,
    MISSING_PROJECTION,
    NO_RELATION_OUTPUT,
    POST_READ_TRANSFORM,
    UNSUPPORTED_OUTPUT_FIELD,
    PROTECTED_FIELD,
    PLAN_HAS_NO_RELATION_PROJECTION
}
