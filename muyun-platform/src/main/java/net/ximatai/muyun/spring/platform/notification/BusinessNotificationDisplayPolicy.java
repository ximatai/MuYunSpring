package net.ximatai.muyun.spring.platform.notification;

/** Presentation only; persistent reminders still have no delivery or acknowledgement persistence. */
public enum BusinessNotificationDisplayPolicy {
    STANDARD("standard"),
    PERSISTENT("persistent");

    private final String value;

    BusinessNotificationDisplayPolicy(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
