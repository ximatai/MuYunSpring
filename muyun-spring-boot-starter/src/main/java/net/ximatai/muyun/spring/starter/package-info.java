/**
 * Spring Boot assembly for a business-owned MuYunSpring application.
 *
 * <h2>Business integration</h2>
 * <p>Import the platform BOM, depend on the Starter and retain the application's own
 * {@code @SpringBootApplication}, domain models and Web declarations. Boot discovers
 * {@link net.ximatai.muyun.spring.starter.MuYunSpringAutoConfiguration} automatically;
 * application code does not need to invoke configuration or bootstrap runners.</p>
 *
 * <p>Configuration property types describe host settings. Assembly classes wire platform
 * collaborators; they are not domain extension points. Declare business behavior through
 * the ability, module and contribution contracts owned by the corresponding domain.</p>
 */
package net.ximatai.muyun.spring.starter;
