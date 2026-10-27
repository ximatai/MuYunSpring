package net.ximatai.muyun.spring.platform.support;

import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL 容器按测试类隔离；使用 Spring 的子类在类结束时先释放上下文及连接池，
 * 再由 Testcontainers 停止容器，避免上下文缓存保留失效的数据源。
 */
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PlatformPostgresIntegrationTest {
    @Container
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
}
