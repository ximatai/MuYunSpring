package net.ximatai.muyun.spring.platform.code;
import net.ximatai.muyun.spring.ability.code.CodeTrigger;
/** Server-selected behavior for one declared field. The candidate is presentation, never record input. */
public record CodeBusinessFieldState(String fieldName, CodeMode mode, CodeTrigger trigger,
                                     boolean readOnly, boolean configured, String preview) { }
