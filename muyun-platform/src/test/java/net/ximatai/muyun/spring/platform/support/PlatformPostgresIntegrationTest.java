package net.ximatai.muyun.spring.platform.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 仅复用 platform Repository IT 的 PostgreSQL 进程；各测试仍维护独立 Spring 上下文和数据契约。
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PlatformPostgresIntegrationTest {
    @Container
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
}
