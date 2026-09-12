package net.ximatai.muyun.spring.ability.logging;

import java.util.Collection;
import java.util.Map;

/**
 * Batch IAM-directory projection for business-log responses.
 *
 * <p>Callers must invoke this only after applying {@link BusinessLogReadScope}; implementations
 * do not infer access from the current reader. Missing or historical records are represented by
 * an absent map entry.</p>
 */
public interface BusinessLogOperatorIdentityLookup {
    Map<BusinessLogOperatorIdentityKey, BusinessLogOperatorIdentity> resolve(
            Collection<BusinessLogOperatorIdentityKey> operatorKeys);
}
