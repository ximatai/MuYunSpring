package net.ximatai.muyun.spring.platform.web;

import java.util.LinkedHashSet;
import java.util.Set;

/** The traits slot names platform behaviour; it does not introduce business-specific actions. */
public record PageTraitsDefinition(Set<PageTrait> values) {
    public PageTraitsDefinition {
        values = values == null ? Set.of() : Set.copyOf(values);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Set<PageTrait> values = new LinkedHashSet<>();
        public Builder standardCrud() { values.add(PageTrait.STANDARD_CRUD); return this; }
        public Builder enabledStatus() { values.add(PageTrait.ENABLED_STATUS); return this; }
        public Builder recycleBin() { values.add(PageTrait.RECYCLE_BIN); return this; }
        public Builder responsiveDetailSurface() { values.add(PageTrait.RESPONSIVE_DETAIL_SURFACE); return this; }
        PageTraitsDefinition build() { return new PageTraitsDefinition(values); }
    }
}
