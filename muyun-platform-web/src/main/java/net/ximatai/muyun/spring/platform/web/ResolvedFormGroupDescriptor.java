package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Client-facing form group and the fields nested inside it. */
public record ResolvedFormGroupDescriptor(String groupCode, String title, String subtitle, List<ViewFieldRef> fields) {
    public ResolvedFormGroupDescriptor {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
