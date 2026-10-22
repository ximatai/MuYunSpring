/**
 * Business-code declarations, configuration, preview, allocation and occupation tracking.
 *
 * <h2>Business integration</h2>
 * <p>Prefer model code declarations and the standard Service save lifecycle. For explicit
 * numbering operations, use {@link net.ximatai.muyun.spring.platform.code.CodeRuntimeFacade}
 * with its command and result types. Preview does not allocate a sequence; allocation alone
 * does not persist a business record. Binding an occupation ledger is also distinct from
 * saving the record through its Service.</p>
 *
 * <p>Rule models and configuration services describe platform-managed numbering policy.
 * Dependency ordering, bucket-key construction, configuration locks and dynamic context
 * assembly are internal collaborators. Business code should not reproduce those steps.</p>
 */
package net.ximatai.muyun.spring.platform.code;
