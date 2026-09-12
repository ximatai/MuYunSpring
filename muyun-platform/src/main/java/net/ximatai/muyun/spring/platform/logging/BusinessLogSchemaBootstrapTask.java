package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

import java.util.Objects;

/** Runs the independently managed log schema bootstrap after the application context is ready. */
public class BusinessLogSchemaBootstrapTask implements PlatformBootstrapTask {
    private final PostgresBusinessLogSchemaInitializer schemaInitializer;

    public BusinessLogSchemaBootstrapTask(PostgresBusinessLogSchemaInitializer schemaInitializer) {
        this.schemaInitializer = Objects.requireNonNull(schemaInitializer, "schemaInitializer must not be null");
    }

    @Override
    public String name() {
        return "platform.business-log-schema";
    }

    @Override
    public int order() {
        return -100;
    }

    @Override
    public void run() {
        schemaInitializer.ensure();
    }
}
