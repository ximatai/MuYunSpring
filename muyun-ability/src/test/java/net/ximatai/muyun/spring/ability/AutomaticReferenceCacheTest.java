package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.reference.ReferenceDependencyRegistryTestAccess;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AutomaticReferenceCacheTest {
    private final DemoCustomerService customers = new DemoCustomerService();
    private final Invoices invoices = new Invoices();

    @AfterEach
    void reset() {
        CacheRegistry.clearAll();
        ReferenceDependencyRegistryTestAccess.clearAll();
        PlatformAbilityRuntime.resetReferenceTargetResolver();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
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
