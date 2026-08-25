package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjection;

import java.util.List;

/**
 * A client-safe, explicitly authorised path below the target of one reference picker.
 *
 * <p>The path is relative to the selected target. Every hop is validated as a declared ONE
 * reference before its terminal field is delivered; it never permits arbitrary browser lookup.</p>
 */
public record ResolvedReferenceSelectionProjectionDescriptor(List<String> path) {
    public ResolvedReferenceSelectionProjectionDescriptor {
        path = path == null ? List.of() : List.copyOf(path);
        new ReferenceSelectionProjection(path);
    }

    public ResolvedReferenceSelectionProjectionDescriptor(ReferenceSelectionProjection projection) {
        this(projection == null ? List.of() : projection.path());
    }

    public ResolvedReferenceSelectionProjectionDescriptor(String projection) {
        this(new ReferenceSelectionProjection(projection));
    }

    public String targetField() {
        return path.getLast();
    }
}
