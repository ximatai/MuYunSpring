package net.ximatai.muyun.spring.common.platform;

public record RecordActionAvailabilityDecision(
        boolean available,
        String reason,
        net.ximatai.muyun.spring.common.i18n.TextReference reasonSource
) {
    public RecordActionAvailabilityDecision {
        reason = normalize(reason);
    }

    public RecordActionAvailabilityDecision(boolean available, String reason) { this(available, reason, null); }

    public static RecordActionAvailabilityDecision unavailableText(net.ximatai.muyun.spring.common.i18n.TextReference source) {
        return new RecordActionAvailabilityDecision(false, source.originalText(), source);
    }

    public static RecordActionAvailabilityDecision allow() {
        return new RecordActionAvailabilityDecision(true, null);
    }

    public static RecordActionAvailabilityDecision unavailable(String reason) {
        return new RecordActionAvailabilityDecision(false, reason);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
