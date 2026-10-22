/**
 * Domain-neutral HTTP protocols, request scopes and Web projection policies.
 *
 * <h2>Business integration</h2>
 * <p>Use the standard static module projections from the platform Web delivery module for
 * ordinary maintenance pages. Implement a typed Web policy only when HTTP context changes
 * the projection, for example {@link net.ximatai.muyun.spring.web.RecordWebProjectionPolicy}.
 * Domain invariants and cross-Service transactions belong in the domain layer.</p>
 *
 * <p>Request/response types document the wire contract. Filters, response advice and protocol
 * serializers are installed by the platform and are not a parallel business execution API.
 * This module does not own IAM, metadata or platform-domain orchestration.</p>
 */
package net.ximatai.muyun.spring.web;
