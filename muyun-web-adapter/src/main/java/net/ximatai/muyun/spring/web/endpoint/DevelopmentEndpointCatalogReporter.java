package net.ximatai.muyun.spring.web.endpoint;

import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeModeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/** Emits the accepted endpoint catalog only for local platform diagnosis. */
public class DevelopmentEndpointCatalogReporter implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(DevelopmentEndpointCatalogReporter.class);

    private final RegisteredWebEndpointCatalog catalog;
    private final PlatformRuntimeModeProvider runtimeModeProvider;

    public DevelopmentEndpointCatalogReporter(RegisteredWebEndpointCatalog catalog,
                                              PlatformRuntimeModeProvider runtimeModeProvider) {
        this.catalog = catalog;
        this.runtimeModeProvider = runtimeModeProvider;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (runtimeModeProvider == null || !runtimeModeProvider.isDevelopment()) {
            return;
        }
        log.info("Platform endpoint catalog: {} registered endpoints", catalog.endpoints().size());
        catalog.endpoints().forEach(endpoint -> {
            ResolvedWebEndpoint definition = endpoint.definition();
            log.debug("Platform endpoint [{}] module={} action={} {} {} source={}",
                    definition.endpointId(), definition.moduleAlias(), definition.action().code(),
                    definition.method(), definition.path(), definition.source());
        });
    }
}
