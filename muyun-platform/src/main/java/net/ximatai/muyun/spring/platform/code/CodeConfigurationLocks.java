package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import java.util.List;

/** A shared model binding and all tenant rule plans must change in one serialized order. */
public final class CodeConfigurationLocks {
    private CodeConfigurationLocks() { }
    public static void lock(String moduleAlias, String entityAlias) {
        if (TransactionScopeSupport.isTransactionActive())
            PlatformAbilityRuntime.lockMutationPartition("platform:business-code-configuration", List.of(moduleAlias, entityAlias).toString());
    }
}
