package net.ximatai.muyun.spring.platform.ui;

/**
 * Server-issued request shape for the built-in local-edit action executor.
 *
 * <p>This is deliberately data-only: a Web runner must not infer a handler or
 * a mutation protocol from a UI block.  The action endpoint remains the
 * authority for action availability, record scope and field validation.</p>
 */
public record LocalEditSubmitContract(
        boolean recordRequired,
        boolean recordVersionRequired,
        boolean fieldNamesRequired,
        String uiConfigIdPayloadKey
) {
    public static final String UI_CONFIG_ID_PAYLOAD_KEY = "uiConfigId";

    public LocalEditSubmitContract {
        if (uiConfigIdPayloadKey == null || uiConfigIdPayloadKey.isBlank()) {
            throw new IllegalArgumentException("local edit ui config payload key must not be blank");
        }
        uiConfigIdPayloadKey = uiConfigIdPayloadKey.trim();
    }

    public static LocalEditSubmitContract standard() {
        return new LocalEditSubmitContract(true, true, true, UI_CONFIG_ID_PAYLOAD_KEY);
    }
}
