/**
 * Static module HTTP projections, page declarations and platform delivery protocols.
 *
 * <h2>Business integration</h2>
 * <p>Use {@link net.ximatai.muyun.spring.platform.web.CrudWeb} for standard maintenance,
 * or {@link net.ximatai.muyun.spring.platform.web.StaticQueryViewWeb} for read-only delivery.
 * Implement {@link net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor} and return
 * a {@link net.ximatai.muyun.spring.platform.web.ModuleUiDefinition} to declare page expression.
 * Retain the native Spring request mapping and the domain Service as the module fact source.</p>
 *
 * <p>DSL values, contribution interfaces and their argument/result types are part of the
 * integration surface. Delivery DTOs describe HTTP values; Controller Java methods are not
 * Service APIs. Compilers and projection planners are platform implementation collaborators:
 * business modules declare intent and let the shared runtime compile and execute it.</p>
 */
package net.ximatai.muyun.spring.platform.web;
