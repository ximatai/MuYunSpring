package net.ximatai.muyun.spring.platform.ui;

/**
 * Optional delivery-adapter hook that prepares executable page facts while a published
 * configuration change is still inside its transaction.
 *
 * <p>The platform module owns the publication transaction, while a delivery adapter owns the
 * compiled runtime representation. Implementations must validate before returning and install
 * the prepared facts only after a successful commit.</p>
 */
@FunctionalInterface
public interface PublishedPageExecutionCoordinator {
    PublishedPageExecutionCoordinator NOOP = moduleAlias -> {
    };

    void prepareAfterPublishedConfigurationChange(String moduleAlias);

    static PublishedPageExecutionCoordinator noop() {
        return NOOP;
    }
}
