package net.ximatai.muyun.spring.ability.query;

/**
 * The authority that supplies a declared external query value.
 *
 * <p>The source is part of the query contract.  It prevents a page UI expression from
 * accidentally taking ownership of a value that is scoped by navigation, session, or another
 * server-controlled context.</p>
 */
public enum ExternalQueryValueSource {
    USER_INPUT,
    PAGE_CONTEXT,
    SESSION_CONTEXT,
    SYSTEM_CONTEXT
}
