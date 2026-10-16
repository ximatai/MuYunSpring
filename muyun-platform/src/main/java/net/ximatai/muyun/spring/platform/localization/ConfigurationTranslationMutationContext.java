package net.ximatai.muyun.spring.platform.localization;

/** Domain command guard: generic CRUD cannot publish a translation or rewrite its source. */
final class ConfigurationTranslationMutationContext {
    private ConfigurationTranslationMutationContext() { }
    enum Command { DRAFT, PUBLISH }
    private static final ThreadLocal<Command> CURRENT = new ThreadLocal<>();
    static Command current() { return CURRENT.get(); }
    static Scope open(Command command) {
        var previous = CURRENT.get();
        CURRENT.set(command);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    interface Scope extends AutoCloseable { @Override void close(); }
}
