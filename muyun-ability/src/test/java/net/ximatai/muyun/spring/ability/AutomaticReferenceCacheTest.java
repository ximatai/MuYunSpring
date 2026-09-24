package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.reference.ReferenceDependencyRegistryTestAccess;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AutomaticReferenceCacheTest {
    private final DemoCustomerService customers = new DemoCustomerService();
    private final Invoices invoices = new Invoices();

    @AfterEach
    void reset() {
        CacheRegistry.resetPolicy();
        ReferenceDependencyRegistryTestAccess.clearAll();
        PlatformAbilityRuntime.resetReferenceTargetResolver();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void oldReadMustNotRecreateRemovedNamespaceOrReferenceRegistrations() {
        DemoCustomer customer = customer("First");
        DemoInvoice invoice = invoice(customer);
        long generation = CacheRegistry.generation();
        CacheRegistry.clearNamespace(invoices.cacheNamespace());
        int namespaces = CacheRegistry.namespaceCount();
        CacheRegistry.putItem(invoices, invoice.getId(), invoice, generation);
        CacheRegistry.putAllCache(invoices, invoices.cacheNamespace(), List.of(invoice), generation);
        assertThat(CacheRegistry.namespaceCount()).isEqualTo(namespaces);
        assertThat(referrers(customer)).isEmpty();
        assertThat(CacheRegistry.item(invoices.cacheNamespace(), invoice.getId())).isNull();
    }

    @Test
    void declaredReferenceMustInvalidateItemAndListCachesWithoutAnExtraAbility() {
        DemoCustomer customer = customer("First");
        DemoInvoice invoice = invoice(customer);
        invoices.select(invoice.getId());
        invoices.selectAllWithCache();

        invoice.setTitle("Changed behind cache");
        assertThat(invoices.select(invoice.getId()).getTitle()).isEqualTo("Invoice");
        customer.setTitle("Renamed");
        customers.update(customer);

        assertThat(invoices.select(invoice.getId()).getTitle()).isEqualTo("Changed behind cache");
        assertThat(invoices.selectAllWithCache()).extracting(DemoInvoice::getTitle)
                .containsExactly("Changed behind cache");
    }

    @Test
    void referenceChangeAndDeletionMustRemoveObsoleteDependencies() {
        DemoCustomer first = customer("First");
        DemoCustomer second = customer("Second");
        DemoInvoice invoice = invoice(first);
        invoices.select(invoice.getId());
        assertThat(referrers(first)).containsExactly(invoice.getId());

        invoice.setCustomerId(second.getId());
        invoices.update(invoice);
        assertThat(referrers(first)).isEmpty();
        invoices.select(invoice.getId());
        assertThat(referrers(second)).containsExactly(invoice.getId());

        invoices.delete(invoice.getId());
        assertThat(referrers(second)).isEmpty();
    }

    @Test
    void targetInvalidationMustWaitForCommit() {
        DemoCustomer customer = customer("First");
        DemoInvoice invoice = invoice(customer);
        invoices.select(invoice.getId());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        customer.setTitle("Renamed");
        customers.update(customer);
        assertThat(CacheRegistry.item(invoices.cacheNamespace(), invoice.getId())).isNotNull();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertThat(CacheRegistry.item(invoices.cacheNamespace(), invoice.getId())).isNull();
    }

    @Test
    void rolledBackInvalidationMustKeepCachedRecordsAndTheirDependencies() {
        DemoCustomer customer = customer("First");
        DemoInvoice invoice = invoice(customer);
        invoices.select(invoice.getId());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        CacheInvalidationSupport.clearAfterChanged(customers, customer);
        CacheInvalidationSupport.clearAfterChanged(invoices, invoice);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(CacheRegistry.item(invoices.cacheNamespace(), invoice.getId())).isNotNull();
        assertThat(referrers(customer)).containsExactly(invoice.getId());
    }

    @Test
    void transactionReadDraftMustNotReplaceDependenciesOfTheSharedCache() {
        DemoCustomer first = customer("First");
        DemoCustomer second = customer("Second");
        DemoInvoice invoice = invoice(first);
        invoices.select(invoice.getId());
        invoices.selectAllWithCache();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        invoices.select(invoice.getId()).setCustomerId(second.getId());
        invoices.selectAllWithCache().getFirst().setCustomerId(second.getId());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThat(referrers(first)).containsExactly(invoice.getId());
        assertThat(referrers(second)).isEmpty();
        customers.update(first);
        assertThat(CacheRegistry.item(invoices.cacheNamespace(), invoice.getId())).isNull();
    }

    @Test
    void transactionOnlyReadsMustNotCreateSharedCacheDependencies() {
        DemoCustomer customer = customer("First");
        DemoInvoice invoice = invoice(customer);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        invoices.select(invoice.getId());
        invoices.selectAllWithCache();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(CacheRegistry.item(invoices.cacheNamespace(), invoice.getId())).isNull();
        assertThat(referrers(customer)).isEmpty();
    }

    @Test
    void clearingServiceCacheMustAlsoForgetItsDependencies() {
        DemoCustomer customer = customer("First");
        DemoInvoice invoice = invoice(customer);
        Invoices other = new Invoices();
        DemoInvoice otherInvoice = new DemoInvoice("Other", List.of());
        otherInvoice.setCustomerId(customer.getId());
        other.insert(otherInvoice);
        other.select(otherInvoice.getId());
        invoices.select(invoice.getId());
        invoices.selectAllWithCache();

        invoices.clearCache();

        assertThat(referrers(customer)).containsExactly(otherInvoice.getId());
        assertThat(CacheRegistry.item(other.cacheNamespace(), otherInvoice.getId())).isNotNull();
    }

    @Test
    void resettingCachePolicyMustAlsoForgetAllDependencies() {
        DemoCustomer customer = customer("First");
        DemoInvoice invoice = invoice(customer);
        invoices.select(invoice.getId());
        invoices.selectAllWithCache();

        CacheRegistry.resetPolicy();

        assertThat(referrers(customer)).isEmpty();
        assertThat(CacheRegistry.namespaceCount()).isZero();
    }

    @Test
    void invalidationMustRetainTheChangedRecordIdUntilCommit() {
        DemoCustomer customer = customer("First");
        DemoInvoice invoice = invoice(customer);
        String id = invoice.getId();
        invoices.select(id);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        CacheInvalidationSupport.clearAfterChanged(invoices, invoice);
        invoice.setId("another-draft");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(CacheRegistry.item(invoices.cacheNamespace(), id)).isNull();
        assertThat(referrers(customer)).isEmpty();
    }

    @Test
    void capacityEvictionMustReleaseOnlyEvictedDependencies() {
        CacheRegistry.configure(new CacheRegistry.CachePolicy(2, Duration.ofMinutes(10)));
        DemoCustomer customer = customer("Customer");
        for (int i = 0; i < 30; i++) invoices.select(invoice(customer).getId());
        CacheRegistry.cleanUp();

        assertThat(referrers(customer)).containsExactlyInAnyOrderElementsOf(
                CacheRegistry.itemIds(invoices.cacheNamespace()));
        assertThat(referrers(customer)).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void listExpiryAndItemRemovalMustKeepDependenciesWhileAnotherCopyExists() {
        AtomicLong clock = new AtomicLong();
        CacheRegistry.useTicker(clock::get);
        CacheRegistry.configure(new CacheRegistry.CachePolicy(2, Duration.ofSeconds(1)));
        DemoCustomer customer = customer("Customer");
        DemoInvoice invoice = invoice(customer);
        invoices.select(invoice.getId());
        invoices.selectAllWithCache();

        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        CacheRegistry.cleanUp();
        assertThat(referrers(customer)).containsExactly(invoice.getId());
        CacheRegistry.removeItem(invoices.cacheNamespace(), invoice.getId());
        assertThat(referrers(customer)).isEmpty();

        invoices.select(invoice.getId());
        invoices.selectAllWithCache();
        CacheRegistry.removeItem(invoices.cacheNamespace(), invoice.getId());
        assertThat(referrers(customer)).containsExactly(invoice.getId());
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        CacheRegistry.cleanUp();
        assertThat(referrers(customer)).isEmpty();
    }

    @Test
    void replacingAnEntryMustNotRemoveAnotherSnapshotsDependencies() {
        DemoCustomer first = customer("First");
        DemoCustomer second = customer("Second");
        DemoInvoice invoice = invoice(first);
        invoices.select(invoice.getId());
        invoices.selectAllWithCache();
        DemoInvoice replacement = invoices.copyForCache(invoice);
        replacement.setCustomerId(second.getId());
        CacheRegistry.putItem(invoices, invoice.getId(), replacement);

        assertThat(referrers(first)).containsExactly(invoice.getId());
        assertThat(referrers(second)).containsExactly(invoice.getId());
        customers.update(first);
        assertThat(CacheRegistry.item(invoices.cacheNamespace(), invoice.getId())).isSameAs(replacement);
        assertThat(referrers(first)).isEmpty();
        assertThat(referrers(second)).containsExactly(invoice.getId());
        customers.update(second);
        assertThat(CacheRegistry.item(invoices.cacheNamespace(), invoice.getId())).isNull();
        assertThat(referrers(second)).isEmpty();
    }

    @Test
    void anInvalidationAlreadyWaitingOnTheCacheMustNotRemoveItsReplacement() throws Exception {
        DemoCustomer first = customer("First");
        DemoCustomer second = customer("Second");
        DemoInvoice invoice = invoice(first);
        invoices.select(invoice.getId());
        DemoInvoice replacement = invoices.copyForCache(invoice);
        replacement.setCustomerId(second.getId());
        Thread invalidation;
        synchronized (CacheRegistry.class) {
            invalidation = Thread.ofPlatform().start(() -> customers.clearReferenceReferrers(first.getId()));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (invalidation.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
            assertThat(invalidation.getState()).isEqualTo(Thread.State.BLOCKED);
            CacheRegistry.putItem(invoices, invoice.getId(), replacement);
        }
        invalidation.join(5000);
        assertThat(invalidation.isAlive()).isFalse();
        assertThat(CacheRegistry.item(invoices.cacheNamespace(), invoice.getId())).isSameAs(replacement);
        assertThat(referrers(first)).isEmpty();
        assertThat(referrers(second)).containsExactly(invoice.getId());
    }

    @Test
    void mutableManyReferencesMustNotEscapeItemOrListSnapshots() {
        DemoCustomer first = customer("First");
        DemoCustomer second = customer("Second");
        ManyInvoices service = new ManyInvoices();
        ManyInvoice record = new ManyInvoice();
        record.customerIds = new java.util.ArrayList<>(List.of(first.getId()));
        service.insert(record);
        service.select(record.getId()).customerIds.add(second.getId());
        service.selectAllWithCache().getFirst().customerIds.clear();

        assertThat(service.select(record.getId()).customerIds).containsExactly(first.getId());
        assertThat(service.selectAllWithCache().getFirst().customerIds).containsExactly(first.getId());
        assertThat(referrers(first)).containsExactly(record.getId());
        assertThat(referrers(second)).isEmpty();
        service.clearCache();
        assertThat(referrers(first)).isEmpty();
    }

    @Test
    void encryptedReferencesMustIndexPlainIdsWithoutChangingStoredOrCachedValues() {
        DemoCustomer customer = customer("Encrypted reference");
        EncryptedInvoices service = new EncryptedInvoices();
        EncryptedInvoice raw = new EncryptedInvoice();
        raw.setId("encrypted-invoice");
        raw.customerId = "enc:" + customer.getId();
        service.getDao().insert(raw);

        assertThat(service.select(raw.getId()).customerId).isEqualTo(customer.getId());
        assertThat(service.selectAllWithCache().getFirst().customerId).isEqualTo(customer.getId());
        assertThat(raw.customerId).isEqualTo("enc:" + customer.getId());
        assertThat(((EncryptedInvoice) CacheRegistry.item(service.cacheNamespace(), raw.getId())).customerId)
                .isEqualTo(raw.customerId);
        assertThat(referrers(customer)).containsExactly(raw.getId());
        customers.update(customer);
        assertThat(CacheRegistry.item(service.cacheNamespace(), raw.getId())).isNull();
        assertThat(referrers(customer)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void aSnapshotInvalidatedDuringItsReadMustNotBeCached(boolean list, boolean referenceChange) {
        DemoCustomer customer = customer("Customer");
        DemoInvoice old = new DemoInvoice("Old", List.of());
        old.setId("invoice-loading");
        old.setCustomerId(customer.getId());
        DemoInvoice fresh = EntityRecordCopies.forCache(old);
        fresh.setTitle("Fresh");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var afterSnapshot = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        InMemoryBaseDao<DemoInvoice> dao = new InMemoryBaseDao<>() {
            @Override public List<DemoInvoice> query(net.ximatai.muyun.database.core.orm.Criteria criteria,
                    net.ximatai.muyun.database.core.orm.PageRequest page,
                    net.ximatai.muyun.database.core.orm.Sort... sorts) {
                if (reads.incrementAndGet() == 1) {
                    afterSnapshot.get().run();
                    return List.of(old);
                }
                return List.of(fresh);
            }
        };
        LoadingInvoices service = new LoadingInvoices(dao);
        afterSnapshot.set(() -> {
            if (referenceChange) customers.clearReferenceReferrers(customer.getId());
            else service.clearItemCache(old.getId());
        });

        assertThat(list ? service.selectAllWithCache().getFirst().getTitle() : service.select(old.getId()).getTitle())
                .isEqualTo("Old");
        assertThat(CacheRegistry.item(service.cacheNamespace(), old.getId())).isNull();
        assertThat(referrers(customer)).isEmpty();
        assertThat(list ? service.selectAllWithCache().getFirst().getTitle() : service.select(old.getId()).getTitle())
                .isEqualTo("Fresh");
        assertThat(list ? service.selectAllWithCache().getFirst().getTitle() : service.select(old.getId()).getTitle())
                .isEqualTo("Fresh");
        assertThat(reads).hasValue(2);
    }

    private static final class LoadingInvoices extends StandardBusinessService<DemoInvoice> implements CacheAbility<DemoInvoice> {
        LoadingInvoices(BaseDao<DemoInvoice, String> dao) { super("demo.loadingInvoice", DemoInvoice.class, dao); }
    }

    static class EncryptedInvoice extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
        @net.ximatai.muyun.spring.common.security.EncryptedField
        @net.ximatai.muyun.spring.ability.reference.ReferenceTo(moduleAlias = "demo", entityAlias = "customer")
        String customerId;
    }

    static class EncryptedInvoices extends StandardBusinessService<EncryptedInvoice>
            implements CacheAbility<EncryptedInvoice>, net.ximatai.muyun.spring.ability.security.FieldProtectionAbility<EncryptedInvoice> {
        EncryptedInvoices() { super("demo.encryptedInvoice", EncryptedInvoice.class, new InMemoryBaseDao<>()); }
        @Override public net.ximatai.muyun.spring.ability.security.FieldCryptoProvider fieldCryptoProvider() {
            return new net.ximatai.muyun.spring.ability.security.FieldCryptoProvider() {
                public String encrypt(String field, Object plain) { return "enc:" + plain; }
                public Object decrypt(String field, String protectedValue) {
                    assertThat(protectedValue).startsWith("enc:");
                    return protectedValue.substring(4);
                }
            };
        }
    }

    static class ManyInvoice extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
        @net.ximatai.muyun.spring.ability.reference.ReferenceTo(moduleAlias = "demo", entityAlias = "customer",
                cardinality = net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.MANY)
        List<String> customerIds;
    }

    static class ManyInvoices extends StandardBusinessService<ManyInvoice> implements CacheAbility<ManyInvoice> {
        ManyInvoices() { super("demo.manyInvoice", ManyInvoice.class, new InMemoryBaseDao<>()); }
    }

    private DemoCustomer customer(String title) {
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> target.equals(customers.referenceTarget())
                ? Optional.of(customers) : Optional.empty());
        DemoCustomer customer = new DemoCustomer(title, "ACTIVE");
        customers.insert(customer);
        return customer;
    }

    private DemoInvoice invoice(DemoCustomer customer) {
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of());
        invoice.setCustomerId(customer.getId());
        invoices.insert(invoice);
        return invoice;
    }

    private java.util.Set<String> referrers(DemoCustomer customer) {
        return ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), customer.getId());
    }

    private static final class Invoices extends StandardBusinessService<DemoInvoice> implements CacheAbility<DemoInvoice> {
        Invoices() { super("demo.autoInvoice", DemoInvoice.class, new InMemoryBaseDao<>()); }
    }
}
