package net.ximatai.muyun.spring.common.i18n;

/** Request or explicitly carried background presentation language; independent of identity and time zone. */
public final class LanguageContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private LanguageContext() { }

    public static String currentTag() { return CURRENT.get(); }

    public static Scope use(String tag) {
        String previous = CURRENT.get();
        if (tag == null) CURRENT.remove();
        else CURRENT.set(LanguageCatalog.canonicalTag(tag));
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed;
        private Scope(String previous) { this.previous = previous; }
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
