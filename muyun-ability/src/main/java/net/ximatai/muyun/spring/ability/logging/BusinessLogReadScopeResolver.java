package net.ximatai.muyun.spring.ability.logging;

/** Resolves the current administrator's storage-neutral business-log visibility range. */
public interface BusinessLogReadScopeResolver {
    BusinessLogReadScope resolve(String moduleAlias, String actionCode);
}
