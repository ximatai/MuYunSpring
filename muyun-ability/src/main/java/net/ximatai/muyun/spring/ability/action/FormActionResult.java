package net.ximatai.muyun.spring.ability.action;

/** Source-neutral typed result for an action over an unsaved form draft. */
public record FormActionResult<T>(T recordPatch, String message) {
    public FormActionResult {
        message = message == null || message.isBlank() ? null : message.trim();
    }
}
