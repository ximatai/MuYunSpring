package net.ximatai.muyun.spring.platform.web;

/** A log-authorized navigation value. Titles are enriched only after log visibility is resolved. */
public record BusinessLogOperatorNavigationItemResponse(String tenantId, String organizationId, String id, String title) {
}
