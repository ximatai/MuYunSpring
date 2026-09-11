package net.ximatai.muyun.spring.platform.ui;
import java.util.List;

/** One Boolean formula controls elements of exactly one form. */
public record UiControlRule(String code, String formKey, String expression, boolean enabled, List<Target> targets) {
    public UiControlRule { targets = targets == null ? List.of() : List.copyOf(targets); }
    public record Target(String elementKey, boolean hide, boolean readOnly) {}
}
