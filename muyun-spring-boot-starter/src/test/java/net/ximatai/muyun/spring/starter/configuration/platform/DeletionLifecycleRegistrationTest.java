package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.deletion.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeletionLifecycleRegistrationTest {
    @Test void primaryConsumerJournalStillReplacesBuiltInWhileContentObserverReceivesItsNode() {
        List<String> observed = new ArrayList<>();
        var builtIn = mock(DeletionLifecycleListener.class);
        DeletionLifecycleListener primary = new DeletionLifecycleListener() {
            @Override public DeletionLifecycleSession open(DeletionResource root) { return new DeletionLifecycleSession() {
            @Override public DeletionNode started(CrudAbility<?> ability, net.ximatai.muyun.spring.common.model.contract.EntityContract entity,
                    DeletionContext context, DeletionMode mode) {
                observed.add("primary-started"); return new DeletionNode("consumer-journal", root);
            }
            @Override public void succeeded(CrudAbility<?> ability, net.ximatai.muyun.spring.common.model.contract.EntityContract entity,
                    DeletionContext context, DeletionNode node, DeletionMode mode) { observed.add("primary-succeeded"); }
            }; }
        };
        DeletionLifecycleObserver observer = (ability, entity, context, node, mode) -> observed.add(node.entryId());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("builtIn", builtIn);
        var override = new RootBeanDefinition(DeletionLifecycleListener.class, () -> primary);
        override.setPrimary(true); beans.registerBeanDefinition("consumer", override);
        beans.registerSingleton("contentObserver", observer);
        var registration = new MuYunSpringMutationConfiguration().deletionLifecycleListenerRegistration(
                beans.getBeanProvider(DeletionLifecycleListener.class), beans.getBeanProvider(DeletionLifecycleObserver.class));
        try {
            BaseDao<Fixture, String> dao = mock(BaseDao.class);
            Fixture row = new Fixture(); row.setId("record");
            when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of(row));
            when(dao.deleteByIdAndVersion("record", 0)).thenReturn(1);
            var service = new AbstractAbilityService<Fixture>("test.content", Fixture.class, dao) { };
            assertThat(service.delete("record")).isEqualTo(1);
            assertThat(observed).containsExactly("primary-started", "consumer-journal", "primary-succeeded");
            verifyNoInteractions(builtIn);
        } finally { registration.destroy(); }
    }
    @Test void observerFailureReachesJournalOnlyAsFailureAndKeepsOriginalException() {
        List<String> observed = new ArrayList<>();
        var cleanup = new IllegalStateException("content cleanup failed");
        DeletionLifecycleListener journal = new DeletionLifecycleListener() {
            @Override public DeletionLifecycleSession open(DeletionResource root) {
                return new DeletionLifecycleSession() {
                    @Override public void succeeded(CrudAbility<?> ability, net.ximatai.muyun.spring.common.model.contract.EntityContract entity,
                            DeletionContext context, DeletionNode node, DeletionMode mode) { observed.add("success"); }
                    @Override public void failed(CrudAbility<?> ability, net.ximatai.muyun.spring.common.model.contract.EntityContract entity,
                            DeletionContext context, DeletionNode node, DeletionMode mode, RuntimeException failure) {
                        assertThat(failure).isSameAs(cleanup); observed.add("failed");
                        throw new IllegalStateException("diagnostic failed");
                    }
                };
            }
        };
        var composite = new CompositeDeletionLifecycleListener(journal, List.of((ability, entity, context, node, mode) -> {
            observed.add("cleanup"); throw cleanup;
        }));
        PlatformAbilityRuntime.configureDeletionLifecycleListener(composite);
        try {
            BaseDao<Fixture, String> dao = mock(BaseDao.class);
            Fixture row = new Fixture(); row.setId("record");
            when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of(row));
            when(dao.deleteByIdAndVersion("record", 0)).thenReturn(1);
            var service = new AbstractAbilityService<Fixture>("test.content", Fixture.class, dao) { };
            assertThatThrownBy(() -> service.delete("record")).isSameAs(cleanup);
            assertThat(observed).containsExactly("cleanup", "failed");
            assertThat(cleanup.getSuppressed()).singleElement().extracting(Throwable::getMessage).isEqualTo("diagnostic failed");
        } finally { PlatformAbilityRuntime.resetDeletionLifecycleListener(); }
    }
    @Test void retainedPurgeNotifiesCopiesWithoutOpeningAnotherDeletionJournal() {
        List<String> observed = new ArrayList<>();
        DeletionLifecycleListener journal = new DeletionLifecycleListener() {
            @Override public DeletionLifecycleSession open(DeletionResource root) {
                throw new AssertionError("Recovery owns the purge journal");
            }
            @Override public void retainedRecordPurged(CrudAbility<?> ability,
                    net.ximatai.muyun.spring.common.model.contract.EntityContract entity) { observed.add("primary-purged"); }
        };
        DeletionLifecycleObserver observer = new DeletionLifecycleObserver() {
            @Override public void succeeded(CrudAbility<?> ability,
                    net.ximatai.muyun.spring.common.model.contract.EntityContract entity,
                    DeletionContext context, DeletionNode node, DeletionMode mode) {
                throw new AssertionError("Purge is distinct from the source deletion");
            }
            @Override public void retainedRecordPurged(CrudAbility<?> ability,
                    net.ximatai.muyun.spring.common.model.contract.EntityContract entity) { observed.add("copies-purged"); }
        };
        PlatformAbilityRuntime.configureDeletionLifecycleListener(new CompositeDeletionLifecycleListener(journal, List.of(observer)));
        try {
            BaseDao<Fixture, String> dao = mock(BaseDao.class);
            Fixture row = new Fixture(); row.setId("record"); row.setDeleted(true);
            when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of(row));
            when(dao.deleteByIdAndVersion("record", 0)).thenReturn(1);
            var service = new RetainedService(dao);
            assertThat(RetainedRecordPurgeSupport.purge(service, "record", 0)).isEqualTo(1);
            assertThat(observed).containsExactly("copies-purged", "primary-purged");
        } finally { PlatformAbilityRuntime.resetDeletionLifecycleListener(); }
    }
    static class RetainedService extends AbstractAbilityService<Fixture> implements SoftDeleteAbility<Fixture> {
        RetainedService(BaseDao<Fixture, String> dao) { super("test.content", Fixture.class, dao); }
    }
    static class Fixture extends StandardEntity { }
}
