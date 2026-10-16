package net.ximatai.muyun.spring.common.content;

import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.Map;
import java.util.Objects;

/** Source-language input for one exact record in the normal business mutation. Never a reading preference. */
public final class ContentWriteContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private ContentWriteContext() { }

    /** Null language means explicitly undetermined; expectedRevision guards a language correction. */
    public record SourceInput(String languageTag, Integer expectedRevision) {
        public SourceInput {
            try { languageTag = languageTag == null ? null : LanguageCatalog.canonicalTag(languageTag); }
            catch (java.util.IllformedLocaleException invalid) { throw new IllegalArgumentException("Invalid source language tag", invalid); }
            if (languageTag != null && languageTag.length() > 255) throw new IllegalArgumentException("Source language tag is too long");
            if (expectedRevision != null && expectedRevision < 1) throw new IllegalArgumentException("Invalid source revision");
        }
    }

    public static Scope open(EntityContract record, Map<String, SourceInput> fields) {
        Scope scope = new Scope(Objects.requireNonNull(record), Map.copyOf(fields), CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static Map<String, SourceInput> inputs(EntityContract record) {
        for (Scope scope = CURRENT.get(); scope != null; scope = scope.previous)
            if (scope.record == record) return scope.fields;
        return Map.of();
    }

    public static final class Scope implements AutoCloseable {
        private final EntityContract record;
        private final Map<String, SourceInput> fields;
        private final Scope previous;
        private Scope(EntityContract record, Map<String, SourceInput> fields, Scope previous) {
            this.record = record; this.fields = fields; this.previous = previous;
        }
        @Override public void close() {
            if (CURRENT.get() != this) throw new IllegalStateException("Content write scopes must close in order");
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
