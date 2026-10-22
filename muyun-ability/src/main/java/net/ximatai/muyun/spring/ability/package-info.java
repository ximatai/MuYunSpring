/**
 * Core platform abilities shared by static services and dynamic runtime adapters.
 *
 * <p>The root package keeps the central CRUD chain, service base class, baseline abilities, cache, tree/sort,
 * exceptions, and internal dispatching. Larger domain-specific ability families live in
 * child and reference subpackages.
 *
 * <h2>Business integration</h2>
 * <p>Start with {@link net.ximatai.muyun.spring.ability.StandardBusinessService}, or
 * {@link net.ximatai.muyun.spring.ability.TenantActiveScopedService} for tenant business data,
 * and compose the required ability interfaces. Declare model facts through the common model
 * contracts and annotations. Soft deletion and caching remain explicit choices.</p>
 *
 * <p>The protected validation templates on the service base classes and the business lifecycle
 * hooks on {@link net.ximatai.muyun.spring.ability.CrudAbility} are extension contracts.
 * Internal dispatchers execute platform capabilities; business hooks do not replace that chain.
 * DAO access is the persistence contract, not an alternative business mutation entry point.</p>
 */
package net.ximatai.muyun.spring.ability;
