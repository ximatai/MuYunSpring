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

        /** Declares the platform operations the page intentionally exposes. */
        public Builder operations(java.util.function.Consumer<OperationsBuilder> customizer) {
            OperationsBuilder builder = new OperationsBuilder(values);
            if (customizer != null) customizer.accept(builder);
            return this;
        }

        /** Declares template presentation behaviour independently of page operations. */
        public Builder presentation(java.util.function.Consumer<PresentationBuilder> customizer) {
            PresentationBuilder builder = new PresentationBuilder(values);
            if (customizer != null) customizer.accept(builder);
            return this;
        }

        /** @deprecated Use {@code operations(operations -> operations.standardCrud())}. */
        @Deprecated(forRemoval = false)
        public Builder standardCrud() { values.add(PageTrait.STANDARD_CRUD); return this; }

        /** @deprecated Use {@code operations(operations -> operations.enabledLifecycle())}. */
        @Deprecated(forRemoval = false)
        public Builder enabledStatus() { values.add(PageTrait.ENABLED_STATUS); return this; }

        /** @deprecated Use {@code operations(operations -> operations.recycleBin())}. */
        @Deprecated(forRemoval = false)
        public Builder recycleBin() { values.add(PageTrait.RECYCLE_BIN); return this; }

        /** @deprecated Use {@code presentation(presentation -> presentation.responsiveDetailSurface())}. */
        @Deprecated(forRemoval = false)
        public Builder responsiveDetailSurface() { values.add(PageTrait.RESPONSIVE_DETAIL_SURFACE); return this; }

        PageTraitsDefinition build() { return new PageTraitsDefinition(values); }
    }

    public static final class OperationsBuilder {
        private final Set<PageTrait> values;

        private OperationsBuilder(Set<PageTrait> values) { this.values = values; }

        public OperationsBuilder standardCrud() { values.add(PageTrait.STANDARD_CRUD); return this; }
        public OperationsBuilder enabledLifecycle() { values.add(PageTrait.ENABLED_STATUS); return this; }
        public OperationsBuilder recycleBin() { values.add(PageTrait.RECYCLE_BIN); return this; }
    }

    public static final class PresentationBuilder {
        private final Set<PageTrait> values;

        private PresentationBuilder(Set<PageTrait> values) { this.values = values; }

        public PresentationBuilder responsiveDetailSurface() {
            values.add(PageTrait.RESPONSIVE_DETAIL_SURFACE);
            return this;
        }
    }
}
