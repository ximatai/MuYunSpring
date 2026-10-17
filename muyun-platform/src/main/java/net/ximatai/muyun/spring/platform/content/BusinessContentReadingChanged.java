package net.ximatai.muyun.spring.platform.content;

/** Reading projection invalidation only; no business values, private drafts or record identities. */
public record BusinessContentReadingChanged(String moduleAlias, String tenantId) {
    public static final String TYPE = net.ximatai.muyun.spring.ability.action.DataChangeTypes.BUSINESS_CONTENT_CHANGED;
}
