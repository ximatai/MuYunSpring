package net.ximatai.muyun.spring.platform.notification;

/**
 * An optional visual emphasis for an ephemeral business reminder.
 *
 * <p>The value does not change delivery, acknowledgement, or action semantics.
 * It only lets the workbench render a business fact with an appropriate standard tone.</p>
 */
public enum BusinessNotificationTone {
    DEFAULT("default"),
    SUCCESS("success"),
    DANGER("danger");

    private final String value;

    BusinessNotificationTone(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
