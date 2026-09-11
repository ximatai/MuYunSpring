package net.ximatai.muyun.spring.ability.logging;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Default synchronous publisher that deliberately propagates storage failures to its caller. */
public final class StoreBackedBusinessLogPublisher implements BusinessLogPublisher {
    private final BusinessLogStore store;

    public StoreBackedBusinessLogPublisher(BusinessLogStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    @Override
    public BusinessLogWriteResult publish(BusinessLogEvent event) {
        return store.append(event);
    }

    @Override
    public List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events) {
        return store.appendAll(events);
    }
}
