package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CompositeEntitySaveLifecycleListenerTest {
    @Test void laterObserverRejectionReleasesEarlierResourcesAndPreservesOriginalFailure() {
        List<String> events = new ArrayList<>();
        RuntimeException rejected = new IllegalStateException("source rejected");
        var resources = new EntitySaveLifecycleListener() {
            @Override public <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) { events.add("bound"); }
            @Override public <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T incoming, RuntimeException failure) { events.add("released"); }
        };
        var source = new EntitySaveLifecycleListener() {
            @Override public <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) { throw rejected; }
            @Override public <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T incoming, RuntimeException failure) { throw new IllegalStateException("cleanup failed"); }
        };
        var listener = new CompositeEntitySaveLifecycleListener(List.of(resources, source));
        var record = new DemoPlainRecord();
        var ability = new AbstractAbilityService<DemoPlainRecord>("test.content", DemoPlainRecord.class, new InMemoryBaseDao<>()) { };
        assertThatThrownBy(() -> listener.beforeSave(ability, null, record)).isSameAs(rejected);
        assertThat(events).containsExactly("bound", "released");
        assertThat(rejected.getSuppressed()).singleElement().extracting(Throwable::getMessage).isEqualTo("cleanup failed");
    }
}
