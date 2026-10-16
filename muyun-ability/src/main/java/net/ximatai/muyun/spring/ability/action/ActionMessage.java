package net.ximatai.muyun.spring.ability.action;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;

public record ActionMessage(
        String code,
        String text,
        ActionMessageType type,
        Map<String, Object> messageArgs,
        TextReference textSource
) {
    public ActionMessage {
        code = normalize(code);
        text = normalize(text);
        type = type == null ? ActionMessageType.SUCCESS : type;
        messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
    }

    public ActionMessage(String code, String text, ActionMessageType type, Map<String, Object> messageArgs) {
        this(code, text, type, messageArgs, null);
    }

    public ActionMessage withTextSource(TextReference source) {
        return new ActionMessage(code, text, type, messageArgs, source);
    }

    public ActionMessage(String code, String text, ActionMessageType type) {
        this(code, text, type, Map.of());
    }

    public static ActionMessage success(String code, String text) {
        return new ActionMessage(code, text, ActionMessageType.SUCCESS);
    }

    public static ActionMessage info(String code, String text) {
        return new ActionMessage(code, text, ActionMessageType.INFO);
    }

    public static ActionMessage warning(String code, String text) {
        return new ActionMessage(code, text, ActionMessageType.WARNING);
    }

    public static ActionMessage error(String code, String text) {
        return new ActionMessage(code, text, ActionMessageType.ERROR);
    }

    public static ActionMessage warning(String code, String text, Map<String, Object> messageArgs) {
        return new ActionMessage(code, text, ActionMessageType.WARNING, messageArgs);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
