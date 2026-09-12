package net.ximatai.muyun.spring.ability.logging;

/** Marker for category-specific details. It deliberately does not expose a generic map. */
public sealed interface BusinessLogDetails permits LoginLogDetails, ActionLogDetails,
        RequestErrorLogDetails, PageAccessLogDetails {
}
