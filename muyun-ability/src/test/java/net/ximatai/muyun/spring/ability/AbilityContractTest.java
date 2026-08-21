package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.Versioned;
import net.ximatai.muyun.spring.common.model.title.TitleField;

import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.child.ChildOf;
import net.ximatai.muyun.spring.ability.child.Children;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceDependencyRegistryTestAccess;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.reference.ReferencerAbility;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleListener;
import net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleSession;
import net.ximatai.muyun.spring.ability.deletion.DeletionMode;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;


import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.title.TitleFieldResolver;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbilityContractTest {
    @AfterEach
    void clearGlobalState() {
        CacheRegistry.clearAll();
        ReferenceDependencyRegistryTestAccess.clearAll();
        CacheRegistry.resetPolicy();
        PlatformAbilityRuntime.resetStaticOptionFieldValueValidator();
        PlatformAbilityRuntime.resetReferenceTargetResolver();
        PlatformAbilityRuntime.resetChildAbilityResolver();
        PlatformAbilityRuntime.resetReferencedByResolver();
        PlatformAbilityRuntime.resetReferenceLoadResolver();
        PlatformAbilityRuntime.resetDeletionLifecycleListener();
        PlatformAbilityRuntime.resetEntitySaveLifecycleListener();
        TenantContext.clear();
        clearTransactionState();
    }

    @Test
    void saveLifecycleShouldObserveFailuresAfterFilePromotionAndBeforeTheSaveCompletes() {
        List<String> failures = new ArrayList<>();
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(new EntitySaveLifecycleListener() {
            @Override
            public <T extends EntityContract> void persistFailed(CrudAbility<T> ability,
                                                                  T entity,
                                                                  RuntimeException failure) {
                failures.add(ability.getModuleAlias() + ":" + failure.getMessage());
            }
        });

        FailingAfterChangedService updateService = new FailingAfterChangedService(false);
        String id = updateService.insert(new DemoPlainRecord("created"));
        updateService.failAfterChanged = true;
        DemoPlainRecord update = new DemoPlainRecord("updated");
        update.setId(id);

        assertThatThrownBy(() -> updateService.update(update))
                .isInstanceOf(PlatformException.class)
                .hasMessage("after changed failed");

        assertThatThrownBy(() -> new FailingAfterChangedService(true).insert(new DemoPlainRecord("created")))
                .isInstanceOf(PlatformException.class)
                .hasMessage("after changed failed");

        assertThatThrownBy(() -> new FailingFieldProtectionService().insert(new DemoPlainRecord("created")))
                .isInstanceOf(PlatformException.class)
                .hasMessage("protected field restore failed");

        assertThat(failures).containsExactly(
                "demo.failingAfterChanged:after changed failed",
                "demo.failingAfterChanged:after changed failed",
                "demo.failingFieldProtection:protected field restore failed"
        );
    }

    @Test
    void crudAbilityShouldFillStandardFieldsAndSoftDelete() {
        DemoOrganizationService service = new DemoOrganizationService();
        DemoOrganization organization = new DemoOrganization("Headquarters", TreeAbility.ROOT_ID);

        String id = service.insert(organization);

        assertThat(id).hasSize(32);
        assertThat(organization.getVersion()).isZero();
        assertThat(organization.getDeleted()).isFalse();
        assertThat(organization.getCreatedAt()).isNotNull();

        assertThat(service.select(id)).isSameAs(organization);

        assertThat(service.delete(id)).isEqualTo(1);
        assertThat(organization.getVersion()).isEqualTo(1);
        assertThat(service.select(id)).isNull();
        assertThat(service.selectIgnoreSoftDelete(id)).isSameAs(organization);
    }

    @Test
    void softDeleteAbilityShouldRestoreOnlyDeletedRecords() {
        DemoOrganizationService service = new DemoOrganizationService();
        DemoOrganization organization = new DemoOrganization("Headquarters", TreeAbility.ROOT_ID);
        String id = service.insert(organization);

        assertThat(service.restore(id)).isZero();
        assertThat(service.delete(id)).isEqualTo(1);
        assertThat(service.restore(id)).isEqualTo(1);
        assertThat(service.select(id)).isNotNull();
        assertThat(organization.getDeletedAt()).isNull();
        assertThat(organization.getDeletedBy()).isNull();
    }

    @Test
    void crudAbilityShouldRunStaticOptionValidationBeforeInsertAndUpdateOnly() {
        DemoOrganizationService service = new DemoOrganizationService();
        List<String> validated = new ArrayList<>();
        PlatformAbilityRuntime.configureStaticOptionFieldValueValidator((modelClass, entity) ->
                validated.add(modelClass.getSimpleName() + ":" + ((DemoOrganization) entity).getTitle()));

        DemoOrganization organization = new DemoOrganization("Headquarters", TreeAbility.ROOT_ID);
        String id = service.insert(organization);
        DemoOrganization update = new DemoOrganization("Updated", TreeAbility.ROOT_ID);
        update.setId(id);
        service.update(update);
        service.delete(id);

        assertThat(validated).containsExactly("DemoOrganization:Headquarters", "DemoOrganization:Updated");
    }

    @Test
    void crudAbilityShouldApplyTenantContextAcrossDefaultEntrypoints() {
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        String id;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            id = service.insert(new DemoPlainRecord("Tenant A"));
            assertThat(service.rawDao().findById(id).getTenantId()).isEqualTo("tenant-a");
            assertThat(service.select(id)).isNotNull();
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            assertThat(service.select(id)).isNull();
            assertThat(service.selectIgnoreSoftDelete(id)).isNull();
            assertThat(service.count(Criteria.of())).isZero();
            DemoPlainRecord update = new DemoPlainRecord("Tenant B overwrite");
            update.setId(id);
            assertThat(service.update(update)).isZero();
            assertThat(service.delete(id)).isZero();
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.select(id).getTitle()).isEqualTo("Tenant A");
            assertThat(service.count(Criteria.of())).isEqualTo(1);
        }
    }

    @Test
    void systemTenantContextShouldUseExplicitUnscopedAccess() {
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        String tenantAId;
        String tenantBId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantAId = service.insert(new DemoPlainRecord("Tenant A"));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            tenantBId = service.insert(new DemoPlainRecord("Tenant B"));
        }

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            assertThat(TenantContext.isSystem()).isTrue();
            assertThat(TenantContext.currentTenantId()).isEmpty();
            assertThat(service.count(Criteria.of())).isEqualTo(2);
            assertThat(service.select(tenantAId)).isNotNull();
            assertThat(service.select(tenantBId)).isNotNull();
        }
    }

    @Test
    void systemTenantContextShouldPreserveTenantWhenUpdatingUnscopedRecord() {
        DemoPlainRecordService service = new DemoPlainRecordService();
        String tenantAId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantAId = service.insert(new DemoPlainRecord("Tenant A"));
        }

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            DemoPlainRecord update = new DemoPlainRecord("System Updated");
            update.setId(tenantAId);
            service.update(update);
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.select(tenantAId).getTitle()).isEqualTo("System Updated");
            assertThat(service.select(tenantAId).getTenantId()).isEqualTo("tenant-a");
        }
    }

    @Test
    void systemTenantContextShouldStillRespectOptimisticLock() {
        DemoPlainRecordService service = new DemoPlainRecordService();
        String tenantAId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            DemoPlainRecord record = new DemoPlainRecord("Tenant A");
            tenantAId = service.insert(record);
            service.update(record);
        }

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            DemoPlainRecord staleUpdate = new DemoPlainRecord("Stale system update");
            staleUpdate.setId(tenantAId);
            staleUpdate.setVersion(0);
            assertThatThrownBy(() -> service.update(staleUpdate))
                    .isInstanceOf(OptimisticLockException.class);

            DemoPlainRecord staleDelete = new DemoPlainRecord("Stale system delete");
            staleDelete.setId(tenantAId);
            staleDelete.setVersion(0);
            assertThatThrownBy(() -> service.delete(staleDelete))
                    .isInstanceOf(OptimisticLockException.class);
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.select(tenantAId).getTitle()).isEqualTo("Tenant A");
            assertThat(service.select(tenantAId).getVersion()).isEqualTo(1);
        }
    }

    @Test
    void crudAbilityShouldDeleteRecordAndBatchByStandardEntry() {
        DemoOrganizationService service = new DemoOrganizationService();
        DemoOrganization first = new DemoOrganization("First", TreeAbility.ROOT_ID);
        DemoOrganization second = new DemoOrganization("Second", TreeAbility.ROOT_ID);
        DemoOrganization third = new DemoOrganization("Third", TreeAbility.ROOT_ID);

        service.insert(first);
        String secondId = service.insert(second);
        String thirdId = service.insert(third);

        assertThat(service.delete(first)).isEqualTo(1);
        assertThat(service.deleteBatch(List.of(secondId, thirdId, "missing"))).isEqualTo(2);
        assertThat(service.select(first.getId())).isNull();
        assertThat(service.select(secondId)).isNull();
        assertThat(service.select(thirdId)).isNull();
    }

    @Test
    void softDeleteAbilityShouldUpdateActiveRecordsOnly() {
        DemoOrganizationService service = new DemoOrganizationService();
        String id = service.insert(new DemoOrganization("Active", TreeAbility.ROOT_ID));

        DemoOrganization maliciousSoftDelete = new DemoOrganization("Updated", TreeAbility.ROOT_ID);
        maliciousSoftDelete.setId(id);
        maliciousSoftDelete.setDeleted(Boolean.TRUE);

        assertThat(service.update(maliciousSoftDelete)).isEqualTo(1);
        assertThat(service.select(id).getTitle()).isEqualTo("Updated");
        assertThat(service.select(id).getDeleted()).isFalse();

        assertThat(service.delete(id)).isEqualTo(1);
        DemoOrganization resurrect = new DemoOrganization("Resurrect", TreeAbility.ROOT_ID);
        resurrect.setId(id);
        resurrect.setDeleted(Boolean.FALSE);

        assertThat(service.update(resurrect)).isZero();
        assertThat(service.select(id)).isNull();
        assertThat(service.selectIgnoreSoftDelete(id).getTitle()).isEqualTo("Updated");
        assertThat(service.selectIgnoreSoftDelete(id).getDeleted()).isTrue();
    }

    @Test
    void crudAbilityShouldStayNeutralWithoutSoftDeleteAbility() {
        DemoPlainRecordService service = new DemoPlainRecordService();
        DemoPlainRecord first = new DemoPlainRecord("First");
        DemoPlainRecord second = new DemoPlainRecord("Second");

        String firstId = service.insert(first);
        String secondId = service.insert(second);
        first.setDeleted(Boolean.TRUE);

        assertThat(service.select(firstId)).isSameAs(first);
        assertThat(service.list(Criteria.of(), PageRequest.of(1, 10)))
                .containsExactly(first, second);
        assertThat(service.pageQuery(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .containsExactly(first, second);
        assertThat(service.deleteBatch(List.of(firstId, secondId))).isEqualTo(2);
        assertThat(service.getDao().findById(firstId)).isNull();
        assertThat(service.getDao().findById(secondId)).isNull();
    }

    @Test
    void enableAbilityShouldBeExplicitAndNotAffectDefaultFiltering() {
        DemoEnabledRecordService service = new DemoEnabledRecordService();
        String enabledId = service.insert(new DemoEnabledRecord("Enabled"));
        String disabledId = service.insert(new DemoEnabledRecord("Disabled"));
        DemoEnabledRecord explicitlyDisabled = new DemoEnabledRecord("Explicitly Disabled");
        explicitlyDisabled.setEnabled(Boolean.FALSE);
        String explicitlyDisabledId = service.insert(explicitlyDisabled);

        assertThat(service.isEnabled(enabledId)).isTrue();
        assertThat(service.isEnabled(explicitlyDisabledId)).isFalse();
        assertThat(service.disable(disabledId)).isEqualTo(1);
        assertThat(service.isEnabled(disabledId)).isFalse();
        assertThat(service.selectIgnoreSoftDelete(disabledId).getVersion()).isEqualTo(1);
        assertThat(service.select(disabledId)).isNotNull();
        assertThat(service.pageQuery(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .extracting(DemoEnabledRecord::getTitle)
                .containsExactly("Enabled", "Disabled", "Explicitly Disabled");
        assertThat(service.pageQuery(service.enabledCriteria(Criteria.of()), PageRequest.of(1, 10)).getRecords())
                .extracting(DemoEnabledRecord::getTitle)
                .containsExactly("Enabled");

        assertThat(service.enable(disabledId)).isEqualTo(1);
        assertThat(service.isEnabled(disabledId)).isTrue();
        assertThat(service.selectIgnoreSoftDelete(disabledId).getVersion()).isEqualTo(2);
    }

    @Test
    void enableAbilityShouldRespectTenantScopeAndSoftDelete() {
        DemoEnabledRecordService service = new DemoEnabledRecordService();
        String tenantAId;

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantAId = service.insert(new DemoEnabledRecord("Tenant A"));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            assertThat(service.disable(tenantAId)).isZero();
            assertThat(service.isEnabled(tenantAId)).isFalse();
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.isEnabled(tenantAId)).isTrue();
        }
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            assertThat(service.disable(tenantAId)).isEqualTo(1);
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.isEnabled(tenantAId)).isFalse();
            assertThat(service.enable(tenantAId)).isEqualTo(1);
            assertThat(service.delete(tenantAId)).isEqualTo(1);
            assertThat(service.disable(tenantAId)).isZero();
            assertThat(service.enable(tenantAId)).isZero();
            assertThat(service.isEnabled(tenantAId)).isFalse();
        }
    }

    @Test
    void crudAbilityShouldUseCurrentVersionForHardDelete() {
        DemoPlainRecordService service = new DemoPlainRecordService();
        DemoPlainRecord record = new DemoPlainRecord("Versioned hard delete");
        String id = service.insert(record);
        service.update(record);

        assertThat(service.delete(id)).isEqualTo(1);

        assertThat(service.rawDao().lastDeleteConditions()).containsEntry("version", 1);
        assertThat(service.getDao().findById(id)).isNull();
    }

    @Test
    void baseDaoVersionMethodsShouldRequireExpectedVersion() {
        DemoPlainRecordService service = new DemoPlainRecordService();
        DemoPlainRecord record = new DemoPlainRecord("Versioned DAO");
        service.insert(record);

        assertThatThrownBy(() -> service.rawDao().updateByIdAndVersion(record, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> service.rawDao().deleteByIdAndVersion(record.getId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedVersion");
    }

    @Test
    void childrenAbilityShouldInsertLoadReplaceAndCascadeDeleteChildren() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine firstLine = new DemoInvoiceLine("First line");
        DemoInvoiceLine secondLine = new DemoInvoiceLine("Second line");
        DemoInvoiceNote firstNote = new DemoInvoiceNote("First note");
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(firstLine, secondLine));
        invoice.setNotes(List.of(firstNote));

        String invoiceId = invoiceService.insert(invoice);

        assertThat(firstLine.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(secondLine.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(firstNote.getInvoiceId()).isEqualTo(invoiceId);

        invoice.setLines(null);
        invoice.setNotes(null);
        DemoInvoice selected = invoiceService.select(invoiceId);
        assertThat(selected.getLines())
                .extracting(DemoInvoiceLine::getTitle)
                .containsExactly("First line", "Second line");
        assertThat(selected.getNotes())
                .extracting(DemoInvoiceNote::getContent)
                .containsExactly("First note");

        firstLine.setTitle("First line updated");
        DemoInvoiceLine thirdLine = new DemoInvoiceLine("Third line");
        invoice.setLines(List.of(firstLine, thirdLine));
        firstNote.setContent("First note updated");
        DemoInvoiceNote secondNote = new DemoInvoiceNote("Second note");
        invoice.setNotes(List.of(firstNote, secondNote));
        invoiceService.update(invoice);

        assertThat(invoiceService.lineService().select(firstLine.getId()).getTitle()).isEqualTo("First line updated");
        assertThat(invoiceService.lineService().select(secondLine.getId())).isNull();
        assertThat(invoiceService.lineService().select(thirdLine.getId())).isNotNull();
        assertThat(invoiceService.noteService().select(firstNote.getId()).getContent()).isEqualTo("First note updated");
        assertThat(invoiceService.noteService().select(secondNote.getId())).isNotNull();

        invoiceService.delete(invoiceId);

        assertThat(invoiceService.select(invoiceId)).isNull();
        assertThat(invoiceService.lineService().select(firstLine.getId())).isNull();
        assertThat(invoiceService.lineService().select(thirdLine.getId())).isNull();
        assertThat(invoiceService.noteService().select(firstNote.getId())).isNull();
        assertThat(invoiceService.noteService().select(secondNote.getId())).isNull();
        assertThat(invoiceService.lineService().selectIgnoreSoftDelete(firstLine.getId())).isNotNull();
        assertThat(invoiceService.lineService().selectIgnoreSoftDelete(thirdLine.getId())).isNotNull();
        assertThat(invoiceService.noteService().selectIgnoreSoftDelete(firstNote.getId())).isNotNull();
        assertThat(invoiceService.noteService().selectIgnoreSoftDelete(secondNote.getId())).isNotNull();
    }

    @Test
    void deleteLifecycleShouldPropagateOneExplicitContextToCascadedChildren() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine line = new DemoInvoiceLine("Line");
        DemoInvoiceNote note = new DemoInvoiceNote("Note");
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(line));
        invoice.setNotes(List.of(note));
        String invoiceId = invoiceService.insert(invoice);
        List<RecordedDeletion> recorded = new ArrayList<>();
        PlatformAbilityRuntime.configureDeletionLifecycleListener(new DeletionLifecycleListener() {
            @Override
            public DeletionLifecycleSession open(net.ximatai.muyun.spring.ability.deletion.DeletionResource root) {
                return new DeletionLifecycleSession() {
                    @Override
                    public DeletionNode started(CrudAbility<?> ability,
                                                EntityContract entity,
                                                DeletionContext context,
                                                DeletionMode mode) {
                        String entryId = "entry-" + (recorded.size() + 1);
                        recorded.add(new RecordedDeletion(ability.getModuleAlias(), entity.getId(), context, entryId, mode));
                        return new DeletionNode(entryId, new net.ximatai.muyun.spring.ability.deletion.DeletionResource(
                                ability.getModuleAlias(), entity.getId()));
                    }
                };
            }
        });

        assertThat(invoiceService.delete(invoiceId)).isEqualTo(1);

        assertThat(recorded).hasSize(3);
        RecordedDeletion root = recorded.getFirst();
        assertThat(root.moduleAlias()).isEqualTo("demo.demoInvoice");
        assertThat(root.context().trigger()).isEqualTo(net.ximatai.muyun.spring.ability.deletion.DeletionTrigger.DIRECT);
        assertThat(root.context().parentEntryId()).isNull();
        assertThat(recorded.subList(1, recorded.size()))
                .allSatisfy(child -> {
                    assertThat(child.context().operationId()).isEqualTo(root.context().operationId());
                    assertThat(child.context().parent()).isEqualTo(root.context().root());
                    assertThat(child.context().parentEntryId()).isEqualTo(root.entryId());
                    assertThat(child.context().trigger()).isEqualTo(net.ximatai.muyun.spring.ability.deletion.DeletionTrigger.CASCADE);
                });
    }

    private record RecordedDeletion(String moduleAlias,
                                    String recordId,
                                    DeletionContext context,
                                    String entryId,
                                    DeletionMode mode) {
    }

    @Test
    void childrenAbilityShouldKeepChildrenWhenPayloadIsNullAndClearWhenEmpty() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine firstLine = new DemoInvoiceLine("First line");
        DemoInvoiceLine secondLine = new DemoInvoiceLine("Second line");
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(firstLine, secondLine));

        String invoiceId = invoiceService.insert(invoice);

        invoice.setLines(null);
        invoiceService.update(invoice);

        assertThat(invoiceService.lineService().select(firstLine.getId())).isNotNull();
        assertThat(invoiceService.lineService().select(secondLine.getId())).isNotNull();

        invoice.setLines(List.of());
        invoiceService.update(invoice);

        assertThat(invoiceService.lineService().select(firstLine.getId())).isNull();
        assertThat(invoiceService.lineService().select(secondLine.getId())).isNull();
        assertThat(invoiceService.select(invoiceId).getLines()).isEmpty();
    }

    @Test
    void childrenAggregationShouldUseChildSortAbilityWhenAvailable() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine firstLine = new DemoInvoiceLine("First line");
        firstLine.setSortOrder(20);
        DemoInvoiceLine secondLine = new DemoInvoiceLine("Second line");
        secondLine.setSortOrder(10);
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(firstLine, secondLine));

        String invoiceId = invoiceService.insert(invoice);

        DemoInvoice selected = invoiceService.select(invoiceId);
        assertThat(selected.getLines())
                .extracting(DemoInvoiceLine::getTitle)
                .containsExactly("Second line", "First line");
    }

    @Test
    void sortPartitionAnnotationShouldKeepChildOrderInsideItsParent() {
        DemoInvoiceLineService service = new DemoInvoiceLineService();
        DemoInvoiceLine first = new DemoInvoiceLine("First");
        first.setInvoiceId("invoice-a");
        DemoInvoiceLine second = new DemoInvoiceLine("Second");
        second.setInvoiceId("invoice-b");
        service.insert(first);
        service.insert(second);

        assertThatThrownBy(() -> service.moveBefore(first.getId(), second.getId()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("invoiceId");
    }

    @Test
    void childrenAbilitySingleRelationShortcutShouldUseAggregateOwnershipDeclaration() {
        SingleChildInvoiceService invoiceService = new SingleChildInvoiceService();
        SingleChildInvoiceLine line = new SingleChildInvoiceLine("Single line");
        SingleChildInvoice invoice = new SingleChildInvoice("Invoice", List.of(line));

        String invoiceId = invoiceService.insert(invoice);

        assertThat(line.getInvoiceId()).isEqualTo(invoiceId);
        invoice.setLines(null);
        assertThat(invoiceService.select(invoiceId).getLines())
                .extracting(SingleChildInvoiceLine::getTitle)
                .containsExactly("Single line");
    }

    @Test
    void childrenAbilitySingleRelationShortcutShouldRejectAmbiguousRelations() {
        DemoInvoiceService service = new DemoInvoiceService();

        assertThatThrownBy(() -> service.childRelation(service.lineService()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("expected exactly one child relation plan")
                .hasMessageContaining("lines")
                .hasMessageContaining("notes");
    }

    @Test
    void childrenAbilityShortcutShouldRequireModelClass() {
        NoModelChildrenService service = new NoModelChildrenService();

        assertThatThrownBy(() -> service.childRelation(new DemoInvoiceLineService()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("demo.noModelChildren")
                .hasMessageContaining("AbstractAbilityService")
                .hasMessageContaining("childRelation(...)");
    }

    @Test
    void childrenAbilityShouldRejectMismatchedRelationCodeAndChildAbility() {
        DemoInvoiceService service = new DemoInvoiceService();

        assertThatThrownBy(() -> service.childRelation("notes", service.lineService()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("child relation model mismatch")
                .hasMessageContaining("notes")
                .hasMessageContaining(DemoInvoiceNote.class.getName())
                .hasMessageContaining(DemoInvoiceLine.class.getName());
    }

    @Test
    void childrenAbilitySinglePlanShortcutShouldRejectMismatchedChildAbility() {
        SingleChildInvoiceService service = new SingleChildInvoiceService();

        assertThatThrownBy(() -> service.childRelation(new DemoInvoiceNoteService()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("child relation model mismatch")
                .hasMessageContaining("lines")
                .hasMessageContaining(SingleChildInvoiceLine.class.getName())
                .hasMessageContaining(DemoInvoiceNote.class.getName());
    }

    @Test
    void platformLifecycleShouldNotDependOnBusinessHooksCallingSuper() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine firstLine = new DemoInvoiceLine("First line");
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(firstLine));
        invoice.setCustomerId("customer-1");

        String invoiceId = invoiceService.insert(invoice);
        assertThat(firstLine.getInvoiceId()).isEqualTo(invoiceId);

        invoice.setLines(null);
        DemoInvoice selected = invoiceService.select(invoiceId);
        assertThat(selected.getLines())
                .extracting(DemoInvoiceLine::getTitle)
                .containsExactly("First line");
        assertThat(selected.getCustomerTitle()).isEqualTo("Customer One");
        assertThat(selected.getCustomerStatus()).isEqualTo("ACTIVE");

        invoice.setLines(List.of());
        invoiceService.update(invoice);
        assertThat(invoiceService.lineService().select(firstLine.getId())).isNull();

        invoiceService.delete(invoiceId);
        assertThat(invoiceService.businessHookCount()).isEqualTo(4);
    }

    @Test
    void platformSelectShouldLoadChildrenWhenParentRecordComesFromCache() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine firstLine = new DemoInvoiceLine("First line");
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(firstLine));
        invoice.setCustomerId("customer-1");
        String invoiceId = invoiceService.insert(invoice);

        invoice.setLines(null);
        DemoInvoice firstSelected = invoiceService.select(invoiceId);
        assertThat(firstSelected.getLines())
                .extracting(DemoInvoiceLine::getTitle)
                .containsExactly("First line");
        assertThat(firstSelected.getCustomerTitle()).isEqualTo("Customer One");
        assertThat(firstSelected.getCustomerStatus()).isEqualTo("ACTIVE");

        invoice.setTitle("Changed behind cache");
        invoice.setCustomerTitle(null);
        invoice.setCustomerStatus(null);
        DemoInvoice secondSelected = invoiceService.select(invoiceId);

        assertThat(secondSelected.getTitle()).isEqualTo("Invoice");
        assertThat(secondSelected.getLines())
                .extracting(DemoInvoiceLine::getTitle)
                .containsExactly("First line");
        assertThat(secondSelected.getCustomerTitle()).isEqualTo("Customer One");
        assertThat(secondSelected.getCustomerStatus()).isEqualTo("ACTIVE");
        assertThat(invoiceService.businessHookCount()).isEqualTo(3);
    }

    @Test
    void cachedSelectShouldRunPlatformLifecycleBeforeBusinessAfterSelect() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of());
        invoice.setCustomerId("customer-1");
        String invoiceId = invoiceService.insert(invoice);

        invoiceService.select(invoiceId);
        invoiceService.select(invoiceId);

        assertThat(invoiceService.lastAfterSelectCustomerTitle()).isEqualTo("Customer One");
    }

    @Test
    void childrenAggregationShouldLoadChildRecordsAsRawData() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine firstLine = new DemoInvoiceLine("First line");
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(firstLine));
        String invoiceId = invoiceService.insert(invoice);

        invoice.setLines(null);
        DemoInvoice selected = invoiceService.select(invoiceId);

        assertThat(selected.getLines())
                .extracting(DemoInvoiceLine::getTitle)
                .containsExactly("First line");
        assertThat(invoiceService.lineService().afterSelectCount()).isZero();

        invoiceService.lineService().select(firstLine.getId());
        assertThat(invoiceService.lineService().afterSelectCount()).isEqualTo(1);
    }

    @Test
    void referenceTargetChangeShouldInvalidateReferrerCache() {
        DemoCustomerService customerService = new DemoCustomerService();
        DemoCustomer customer = new DemoCustomer("Customer One", "ACTIVE");
        customer.setId("customer-1");
        customerService.insert(customer);
        DemoInvoiceService invoiceService = new DemoInvoiceService(customerService);
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(new DemoInvoiceLine("First line")));
        invoice.setCustomerId("customer-1");
        String invoiceId = invoiceService.insert(invoice);

        DemoInvoice firstSelected = invoiceService.select(invoiceId);
        assertThat(firstSelected.getCustomerTitle()).isEqualTo("Customer One");
        assertThat(firstSelected.getCustomerStatus()).isEqualTo("ACTIVE");
        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-1"))
                .contains(invoiceId);

        invoice.setTitle("Changed behind cache");
        customer.setTitle("Customer Updated");
        customer.setStatus("SUSPENDED");
        customerService.update(customer);

        DemoInvoice secondSelected = invoiceService.select(invoiceId);
        assertThat(secondSelected.getTitle()).isEqualTo("Changed behind cache");
        assertThat(secondSelected.getCustomerTitle()).isEqualTo("Customer Updated");
        assertThat(secondSelected.getCustomerStatus()).isEqualTo("SUSPENDED");
    }

    @Test
    void referenceTargetChangeShouldInvalidateReferrerAllCache() {
        DemoCustomerService customerService = new DemoCustomerService();
        DemoCustomer customer = new DemoCustomer("Customer One", "ACTIVE");
        customer.setId("customer-1");
        customerService.insert(customer);
        DemoInvoiceService invoiceService = new DemoInvoiceService(customerService);
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of());
        invoice.setCustomerId("customer-1");
        invoiceService.insert(invoice);

        assertThat(invoiceService.selectAllWithCache())
                .extracting(DemoInvoice::getTitle)
                .containsExactly("Invoice");

        invoice.setTitle("Changed behind all cache");
        customer.setTitle("Customer Updated");
        customer.setStatus("SUSPENDED");
        customerService.update(customer);

        DemoInvoice selected = invoiceService.selectAllWithCache().getFirst();
        assertThat(selected.getTitle()).isEqualTo("Changed behind all cache");
        assertThat(selected.getCustomerTitle()).isEqualTo("Customer Updated");
        assertThat(selected.getCustomerStatus()).isEqualTo("SUSPENDED");
    }

    @Test
    void referenceDependencyRegistryShouldMoveReferrerWhenReferenceValueChanges() {
        DemoCustomerService customerService = new DemoCustomerService();
        DemoCustomer firstCustomer = new DemoCustomer("First Customer", "ACTIVE");
        firstCustomer.setId("customer-1");
        customerService.insert(firstCustomer);
        DemoCustomer secondCustomer = new DemoCustomer("Second Customer", "ACTIVE");
        secondCustomer.setId("customer-2");
        customerService.insert(secondCustomer);
        DemoInvoiceService invoiceService = new DemoInvoiceService(customerService);
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of());
        invoice.setCustomerId("customer-1");
        String invoiceId = invoiceService.insert(invoice);

        invoiceService.select(invoiceId);
        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-1"))
                .containsExactly(invoiceId);

        invoice.setCustomerId("customer-2");
        invoiceService.update(invoice);
        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-1"))
                .isEmpty();

        invoiceService.select(invoiceId);
        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-2"))
                .containsExactly(invoiceId);

        invoice.setTitle("Changed behind cache");
        firstCustomer.setTitle("First Customer Updated");
        customerService.update(firstCustomer);
        assertThat(invoiceService.select(invoiceId).getTitle()).isEqualTo("Invoice");

        secondCustomer.setTitle("Second Customer Updated");
        customerService.update(secondCustomer);
        assertThat(invoiceService.select(invoiceId).getTitle()).isEqualTo("Changed behind cache");
    }

    @Test
    void referenceDependencyRegistryShouldRemoveReferrerWhenReferenceIsClearedOrDeleted() {
        DemoCustomerService customerService = new DemoCustomerService();
        DemoCustomer customer = new DemoCustomer("Customer", "ACTIVE");
        customer.setId("customer-1");
        customerService.insert(customer);
        DemoInvoiceService invoiceService = new DemoInvoiceService(customerService);
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of());
        invoice.setCustomerId("customer-1");
        String invoiceId = invoiceService.insert(invoice);

        invoiceService.select(invoiceId);
        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-1"))
                .containsExactly(invoiceId);

        invoice.setCustomerId(null);
        invoiceService.update(invoice);
        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-1"))
                .isEmpty();

        invoice.setCustomerId("customer-1");
        invoiceService.update(invoice);
        invoiceService.select(invoiceId);
        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-1"))
                .containsExactly(invoiceId);

        invoiceService.delete(invoiceId);
        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-1"))
                .isEmpty();
    }

    @Test
    void referenceDependencyRegistryShouldClearReferrersByNamespacePrefix() {
        DemoCustomerService customerService = new DemoCustomerService();
        DemoCustomer customer = new DemoCustomer("Customer", "ACTIVE");
        customer.setId("customer-1");
        customerService.insert(customer);
        DemoInvoiceService invoiceService = new DemoInvoiceService(customerService);
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of());
        invoice.setCustomerId("customer-1");
        String invoiceId = invoiceService.insert(invoice);

        invoiceService.select(invoiceId);
        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-1"))
                .containsExactly(invoiceId);

        String namespacePrefix = invoiceService.cacheNamespace()
                .substring(0, invoiceService.cacheNamespace().lastIndexOf("::"));
        ReferenceDependencyRegistryTestAccess.clearNamespacePrefix(namespacePrefix);

        assertThat(ReferenceDependencyRegistryTestAccess.referrerIds(ReferenceTarget.of("demo", "customer"), "customer-1"))
                .isEmpty();
    }

    @Test
    void childrenAbilityShouldRejectDuplicateAndForeignChildIds() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoice firstInvoice = new DemoInvoice("First invoice", List.of(new DemoInvoiceLine("First line")));
        DemoInvoice secondInvoice = new DemoInvoice("Second invoice", List.of(new DemoInvoiceLine("Second line")));
        invoiceService.insert(firstInvoice);
        invoiceService.insert(secondInvoice);
        DemoInvoiceLine firstLine = firstInvoice.getLines().getFirst();
        DemoInvoiceLine secondLine = secondInvoice.getLines().getFirst();

        firstInvoice.setLines(List.of(firstLine, firstLine));
        assertThatThrownBy(() -> invoiceService.update(firstInvoice))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Duplicate child id");

        firstInvoice.setLines(List.of(secondLine));
        assertThatThrownBy(() -> invoiceService.update(firstInvoice))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not belong to parent");
    }

    @Test
    void childrenAbilityShouldKeepAggregationInsideCurrentTenant() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        String tenantAInvoiceId;
        DemoInvoiceLine tenantBLine;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantAInvoiceId = invoiceService.insert(new DemoInvoice("Tenant A invoice", List.of(new DemoInvoiceLine("Tenant A line"))));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            DemoInvoice tenantBInvoice = new DemoInvoice("Tenant B invoice", List.of(new DemoInvoiceLine("Tenant B line")));
            invoiceService.insert(tenantBInvoice);
            tenantBLine = tenantBInvoice.getLines().getFirst();
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            DemoInvoice selected = invoiceService.select(tenantAInvoiceId);
            assertThat(selected.getLines())
                    .extracting(DemoInvoiceLine::getTitle)
                    .containsExactly("Tenant A line");
            selected.setLines(List.of(tenantBLine));
            assertThatThrownBy(() -> invoiceService.update(selected))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("does not belong to parent");
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            assertThat(invoiceService.lineService().select(tenantBLine.getId()).getTitle()).isEqualTo("Tenant B line");
        }
    }

    @Test
    void childrenAggregationShouldHideSoftDeletedChildRecords() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine activeLine = new DemoInvoiceLine("Active line");
        DemoInvoiceLine deletedLine = new DemoInvoiceLine("Deleted line");
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(activeLine, deletedLine));
        String invoiceId = invoiceService.insert(invoice);

        invoiceService.lineService().delete(deletedLine.getId());

        DemoInvoice selected = invoiceService.select(invoiceId);
        assertThat(selected.getLines())
                .extracting(DemoInvoiceLine::getTitle)
                .containsExactly("Active line");
        assertThat(invoiceService.lineService().selectIgnoreSoftDelete(deletedLine.getId())).isNotNull();
    }

    @Test
    void childrenAbilityShouldRejectInvalidChildIdsOnParentInsert() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine duplicateLine = new DemoInvoiceLine("Duplicate line");
        duplicateLine.setId("same-line");

        assertThatThrownBy(() -> invoiceService.insert(new DemoInvoice("Duplicate invoice", List.of(duplicateLine, duplicateLine))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Duplicate child id");

        DemoInvoice existingInvoice = new DemoInvoice("Existing invoice", List.of(new DemoInvoiceLine("Existing line")));
        invoiceService.insert(existingInvoice);
        DemoInvoiceLine existingLine = existingInvoice.getLines().getFirst();

        assertThatThrownBy(() -> invoiceService.insert(new DemoInvoice("Foreign invoice", List.of(existingLine))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not belong to parent");
    }

    @Test
    void cacheAbilityShouldReturnCopiesAndInvalidateAfterChange() {
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        DemoPlainRecord record = new DemoPlainRecord("Cached");
        String id = service.insert(record);

        DemoPlainRecord selected = service.select(id);
        selected.setTitle("Changed outside cache");
        service.rawDao().findById(id).setTitle("Changed behind cache");

        assertThat(service.select(id).getTitle()).isEqualTo("Cached");

        DemoPlainRecord updated = new DemoPlainRecord("Updated");
        updated.setId(id);
        service.update(updated);

        assertThat(service.select(id).getTitle()).isEqualTo("Updated");
        assertThat(service.afterChangedCount()).isEqualTo(2);
    }

    @Test
    void cacheAbilityShouldBypassItemAndAllCacheInsideTransaction() {
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        DemoPlainRecord record = new DemoPlainRecord("Cached");
        String id = service.insert(record);
        service.select(id);
        service.selectAllWithCache();
        service.rawDao().findById(id).setTitle("Changed in transaction");
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThat(service.select(id).getTitle()).isEqualTo("Changed in transaction");
        assertThat(service.selectAllWithCache())
                .extracting(DemoPlainRecord::getTitle)
                .containsExactly("Changed in transaction");

        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThat(service.select(id).getTitle()).isEqualTo("Cached");
        assertThat(service.selectAllWithCache())
                .extracting(DemoPlainRecord::getTitle)
                .containsExactly("Cached");
    }

    @Test
    void cacheAbilityDefaultCopyShouldSkipChildrenAndTransientFields() {
        DemoInvoiceService invoiceService = new DemoInvoiceService();
        DemoInvoiceLine firstLine = new DemoInvoiceLine("First line");
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of(firstLine));
        invoice.setCustomerId("customer-1");
        String invoiceId = invoiceService.insert(invoice);

        DemoInvoice selected = invoiceService.select(invoiceId);

        DemoInvoice cached = (DemoInvoice) CacheRegistry.item(invoiceService.cacheNamespace(), invoiceId);
        assertThat(cached.getLines()).isNull();
        assertThat(cached.getNotes()).isNull();
        assertThat(cached.getCustomerTitle()).isNull();
        assertThat(cached.getCustomerStatus()).isNull();
        assertThat(selected.getLines())
                .extracting(DemoInvoiceLine::getTitle)
                .containsExactly("First line");
        assertThat(selected.getCustomerTitle()).isEqualTo("Customer One");
        assertThat(selected.getCustomerStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void cacheAbilityDefaultCopyShouldExplainMissingNoArgConstructor() {
        NoNoArgCachedRecordService service = new NoNoArgCachedRecordService();
        String id = service.insert(new NoNoArgCachedRecord("No arg required"));

        assertThatThrownBy(() -> service.select(id))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no-arg constructor")
                .hasMessageContaining("custom copyForCache")
                .hasMessageContaining(NoNoArgCachedRecord.class.getName());
    }

    @Test
    void cacheAbilityShouldCacheAllAndHideSoftDeletedRows() {
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        DemoPlainRecord first = new DemoPlainRecord("First");
        DemoPlainRecord second = new DemoPlainRecord("Second");
        String firstId = service.insert(first);
        service.insert(second);

        assertThat(service.selectAllWithCache()).extracting(DemoPlainRecord::getTitle)
                .containsExactly("First", "Second");

        service.rawDao().findById(firstId).setTitle("Changed behind all cache");
        assertThat(service.selectAllWithCache()).extracting(DemoPlainRecord::getTitle)
                .containsExactly("First", "Second");

        service.delete(firstId);

        assertThat(service.selectAllWithCache()).extracting(DemoPlainRecord::getTitle)
                .containsExactly("Second");
        assertThat(service.select(firstId)).isNull();
        assertThat(service.selectIgnoreSoftDelete(firstId)).isNotNull();
    }

    @Test
    void cacheAbilityShouldKeepAllCacheInsideCurrentTenantScope() {
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        String tenantAId;
        String tenantBId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantAId = service.insert(new DemoPlainRecord("Tenant A"));
            assertThat(service.selectAllWithCache())
                    .extracting(DemoPlainRecord::getTitle)
                    .containsExactly("Tenant A");
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            tenantBId = service.insert(new DemoPlainRecord("Tenant B"));
            assertThat(service.selectAllWithCache())
                    .extracting(DemoPlainRecord::getTitle)
                    .containsExactly("Tenant B");
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.selectAllWithCache())
                    .extracting(DemoPlainRecord::getTitle)
                    .containsExactly("Tenant A");
            service.rawDao().findById(tenantAId).setTitle("Tenant A behind cache");
            assertThat(service.selectAllWithCache())
                    .extracting(DemoPlainRecord::getTitle)
                    .containsExactly("Tenant A");
        }
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            assertThat(service.selectAllWithCache())
                    .extracting(DemoPlainRecord::getTitle)
                    .containsExactly("Tenant A behind cache", "Tenant B");
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            DemoPlainRecord update = new DemoPlainRecord("Tenant B updated");
            update.setId(tenantBId);
            service.update(update);
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.selectAllWithCache())
                    .extracting(DemoPlainRecord::getTitle)
                    .containsExactly("Tenant A behind cache");
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            assertThat(service.selectAllWithCache())
                    .extracting(DemoPlainRecord::getTitle)
                    .containsExactly("Tenant B updated");
        }
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            assertThat(service.selectAllWithCache())
                    .extracting(DemoPlainRecord::getTitle)
                    .containsExactly("Tenant A behind cache", "Tenant B updated");
        }
    }

    @Test
    void cacheAbilityShouldIsolateServicesWithSameModuleAlias() {
        DemoCachedPlainRecordService firstService = new DemoCachedPlainRecordService();
        DemoCachedPlainRecordService secondService = new DemoCachedPlainRecordService();
        DemoPlainRecord record = new DemoPlainRecord("First service only");
        String id = firstService.insert(record);

        assertThat(firstService.select(id)).isNotNull();

        assertThat(secondService.select(id)).isNull();
    }

    @Test
    void cacheRegistryShouldClearNamespacesByPrefix() {
        DemoCachedPlainRecordService firstService = new DemoCachedPlainRecordService();
        DemoCachedPlainRecordService secondService = new DemoCachedPlainRecordService();
        String firstId = firstService.insert(new DemoPlainRecord("First namespace"));
        String secondId = secondService.insert(new DemoPlainRecord("Second namespace"));
        firstService.select(firstId);
        secondService.select(secondId);

        assertThat(CacheRegistry.namespaceCount()).isEqualTo(2);

        CacheRegistry.clearNamespacePrefix(firstService.cacheNamespace());

        assertThat(CacheRegistry.namespaceCount()).isEqualTo(1);
        assertThat(firstService.select(firstId)).isNotNull();
        assertThat(CacheRegistry.namespaceCount()).isEqualTo(2);
    }

    @Test
    void cacheRegistryShouldClearNamespacePrefixBySegmentBoundary() {
        CacheRegistry.putItem("dynamic-runtime-1::sales.contract", "first", new DemoPlainRecord("First"));
        CacheRegistry.putItem("dynamic-runtime-10::sales.contract", "second", new DemoPlainRecord("Second"));

        CacheRegistry.clearNamespacePrefix("dynamic-runtime-1");

        assertThat(CacheRegistry.namespaceCount()).isEqualTo(1);
        assertThat(CacheRegistry.itemIds("dynamic-runtime-10::sales.contract")).containsExactly("second");
    }

    @Test
    void cacheInvalidationShouldNotCreateEmptyItemNamespaceWhenOnlyAllCacheExists() {
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        DemoPlainRecord record = new DemoPlainRecord("All cache only");
        String id = service.insert(record);
        service.selectAllWithCache();

        assertThat(CacheRegistry.namespaceCount()).isEqualTo(1);

        DemoPlainRecord update = new DemoPlainRecord("Updated");
        update.setId(id);
        service.update(update);

        assertThat(CacheRegistry.namespaceCount()).isZero();
    }

    @Test
    void cacheRegistryShouldKeepItemCacheBoundedPerNamespace() {
        CacheRegistry.configure(new CacheRegistry.CachePolicy(2, Duration.ofMinutes(10)));
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        String first = service.insert(new DemoPlainRecord("First"));
        String second = service.insert(new DemoPlainRecord("Second"));
        String third = service.insert(new DemoPlainRecord("Third"));

        service.select(first);
        service.select(second);
        service.select(third);

        assertThat(CacheRegistry.itemIds(service.cacheNamespace()))
                .hasSizeLessThanOrEqualTo(2)
                .isSubsetOf(first, second, third);
    }

    @Test
    void cacheRegistryShouldExpireAllCacheByPolicy() throws InterruptedException {
        CacheRegistry.configure(new CacheRegistry.CachePolicy(1024, Duration.ofMillis(20)));
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        String id = service.insert(new DemoPlainRecord("Before ttl"));
        assertThat(service.selectAllWithCache()).extracting(DemoPlainRecord::getTitle)
                .containsExactly("Before ttl");

        service.rawDao().findById(id).setTitle("After ttl");
        Thread.sleep(50);

        assertThat(service.selectAllWithCache()).extracting(DemoPlainRecord::getTitle)
                .containsExactly("After ttl");
    }

    @Test
    void cacheRegistryShouldClearAllCacheNamespaceByPrefix() {
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        service.insert(new DemoPlainRecord("All cache prefix"));
        service.selectAllWithCache();

        assertThat(CacheRegistry.namespaceCount()).isEqualTo(1);
        assertThat(CacheRegistry.itemIds(service.cacheNamespace())).isEmpty();

        CacheRegistry.clearNamespacePrefix(service.cacheNamespace());

        assertThat(CacheRegistry.namespaceCount()).isZero();
    }

    @Test
    void referencerAbilityShouldCollectReferenceIdsByStaticAnnotations() {
        DemoReferencingRecordService service = new DemoReferencingRecordService();
        DemoReferencingRecord record = new DemoReferencingRecord("customer-1", "user-owner");
        record.setWatcherIds("user-watcher-1, user-watcher-2, user-owner");

        assertThat(service.collectReferenceIdsByTarget(record))
                .containsEntry(ReferenceTarget.of("demo", "customer"), java.util.Set.of("customer-1"))
                .containsEntry(ReferenceTarget.of("iam", "user"),
                        java.util.Set.of("user-owner", "user-watcher-1", "user-watcher-2"));
        assertThat(StaticReferenceResolver.rules(DemoReferencingRecord.class))
                .extracting(StaticReferenceResolver.ReferenceRule::target)
                .containsExactly(
                        ReferenceTarget.of("demo", "customer"),
                        ReferenceTarget.of("iam", "user"),
                        ReferenceTarget.of("iam", "user")
                );

        String id = service.insert(record);
        DemoReferencingRecord selected = service.select(id);
        assertThat(selected.getCustomerTitle()).isEqualTo("Customer One");
        assertThat(selected.getCustomerStatus()).isEqualTo("ACTIVE");
        assertThat(selected.getOwnerTitle()).isEqualTo("Owner One");
    }

    @Test
    void referenceToShouldValidateWritesWithoutReferencerAbility() {
        DemoCustomerService customerService = new DemoCustomerService();
        String customerId = customerService.insert(new DemoCustomer("Customer One", "ACTIVE"));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target ->
                ReferenceTarget.of("demo", "customer").equals(target)
                        ? Optional.of(customerService)
                        : Optional.empty());
        PlainReferenceRecordService service = new PlainReferenceRecordService();

        service.insert(new PlainReferenceRecord(customerId));

        assertThatThrownBy(() -> service.insert(new PlainReferenceRecord("missing-customer")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reference target is unavailable")
                .hasMessageContaining("demo.customer.customerId");
    }

    @Test
    void referenceToShouldPreserveExistingUnavailableTargetOnUpdate() {
        DemoCustomerService customerService = new DemoCustomerService();
        String customerId = customerService.insert(new DemoCustomer("Customer One", "ACTIVE"));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target ->
                ReferenceTarget.of("demo", "customer").equals(target)
                        ? Optional.of(customerService)
                        : Optional.empty());
        PlainReferenceRecordService service = new PlainReferenceRecordService();
        PlainReferenceRecord record = new PlainReferenceRecord(customerId);
        service.insert(record);

        customerService.delete(customerId);

        service.update(record);
    }

    @Test
    void referencerAbilityShouldPreserveExistingUnavailableTargetOnUpdate() {
        DemoCustomerService customerService = new DemoCustomerService();
        String customerId = customerService.insert(new DemoCustomer("Customer One", "ACTIVE"));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target ->
                ReferenceTarget.of("demo", "customer").equals(target)
                        ? Optional.of(customerService)
                        : Optional.empty());
        ReferencingPlainRecordService service = new ReferencingPlainRecordService();
        ReferencingPlainRecord record = new ReferencingPlainRecord(customerId);
        service.insert(record);

        customerService.delete(customerId);

        assertThat(service.update(record)).isEqualTo(1);
        ReferencingPlainRecord replacement = new ReferencingPlainRecord("missing-customer");
        replacement.setId(record.getId());
        replacement.setVersion(record.getVersion());
        assertThatThrownBy(() -> service.update(replacement))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reference target is unavailable");
    }

    @Test
    void referenceToShouldRejectRestoreWhenTargetIsUnavailable() {
        DemoCustomerService customerService = new DemoCustomerService();
        String customerId = customerService.insert(new DemoCustomer("Customer One", "ACTIVE"));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target ->
                ReferenceTarget.of("demo", "customer").equals(target)
                        ? Optional.of(customerService)
                        : Optional.empty());
        SoftReferenceRecordService service = new SoftReferenceRecordService();
        String recordId = service.insert(new PlainReferenceRecord(customerId));
        assertThat(service.delete(recordId)).isEqualTo(1);

        customerService.delete(customerId);

        assertThatThrownBy(() -> service.restore(recordId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reference target is unavailable");
    }

    @Test
    void referencerAbilityShouldUseServiceModelClassWhenPresent() {
        StaticReferenceBaseService service = new StaticReferenceBaseService();
        StaticReferenceProxyRecord proxyRecord = new StaticReferenceProxyRecord("customer-1");

        assertThat(service.collectReferenceIdsByTarget(proxyRecord)).isEmpty();
    }

    @Test
    void crudAbilityShouldIncreaseVersionOnUpdate() {
        DemoOrganizationService service = new DemoOrganizationService();
        DemoOrganization organization = new DemoOrganization("Versioned", TreeAbility.ROOT_ID);
        service.insert(organization);

        service.update(organization);

        assertThat(organization.getVersion()).isEqualTo(1);
        assertThat(organization.getUpdatedAt()).isAfterOrEqualTo(organization.getCreatedAt());
    }

    @Test
    void crudAbilityShouldRejectStaleVersionUpdate() {
        DemoPlainRecordService service = new DemoPlainRecordService();
        DemoPlainRecord record = new DemoPlainRecord("Versioned");
        String id = service.insert(record);
        service.update(record);

        DemoPlainRecord stale = new DemoPlainRecord("Stale");
        stale.setId(id);
        stale.setVersion(0);

        assertThatThrownBy(() -> service.update(stale))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("version conflict");
        assertThat(service.select(id).getTitle()).isEqualTo("Versioned");
    }

    @Test
    void softDeleteAbilityShouldRejectStaleVersionDelete() {
        DemoCachedPlainRecordService service = new DemoCachedPlainRecordService();
        DemoPlainRecord record = new DemoPlainRecord("Versioned");
        String id = service.insert(record);
        service.update(record);

        DemoPlainRecord stale = new DemoPlainRecord("Stale");
        stale.setId(id);
        stale.setVersion(0);

        assertThatThrownBy(() -> service.delete(stale))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("version conflict");
        assertThat(service.select(id)).isNotNull();
    }

    @Test
    void treeAbilityShouldResolveChildrenAndAncestors() {
        DemoOrganizationService service = new DemoOrganizationService();
        DemoOrganization rootChild = new DemoOrganization("Region", TreeAbility.ROOT_ID);
        DemoOrganization leaf = new DemoOrganization("Branch", null);
        DemoOrganization subLeaf = new DemoOrganization("Desk", null);

        String regionId = service.insert(rootChild);
        leaf.setParentId(regionId);
        String leafId = service.insert(leaf);
        subLeaf.setParentId(leafId);
        String subLeafId = service.insert(subLeaf);

        assertThat(service.children(regionId)).containsExactly(leaf);
        assertThat(service.ancestorIds(leafId)).containsExactly(regionId);
        assertThat(service.ancestorIdsAndSelf(leafId)).containsExactly(regionId, leafId);
        assertThat(service.ancestorIds(subLeafId)).containsExactly(regionId, leafId);
        assertThat(service.descendantIds(regionId)).containsExactly(leafId, subLeafId);
        assertThat(service.selfAndDescendantIds(regionId)).containsExactly(regionId, leafId, subLeafId);
        assertThat(service.selfAndDescendantIds("")).isEmpty();
        assertThat(service.ancestorIdsAndSelf("missing")).isEmpty();
    }

    @Test
    void treeAbilityShouldSupportScopedChildrenAndPlacementValidation() {
        DemoOrganizationService service = new DemoOrganizationService();
        DemoOrganization scopeAParent = new DemoOrganization("A Parent", TreeAbility.ROOT_ID);
        scopeAParent.setScopeKey("scope-a");
        DemoOrganization scopeBParent = new DemoOrganization("B Parent", TreeAbility.ROOT_ID);
        scopeBParent.setScopeKey("scope-b");

        String scopeAParentId = service.insert(scopeAParent);
        String scopeBParentId = service.insert(scopeBParent);
        DemoOrganization scopeAChild = new DemoOrganization("A Child", scopeAParentId);
        scopeAChild.setScopeKey("scope-a");
        service.validateTreePlacementInScope(scopeAChild, Criteria.of().eq("scopeKey", "scope-a"), "parent must match scope");
        String scopeAChildId = service.insert(scopeAChild);

        assertThat(service.children(Criteria.of().eq("scopeKey", "scope-a"), TreeAbility.ROOT_ID))
                .extracting(DemoOrganization::getId)
                .containsExactly(scopeAParentId);
        assertThat(service.children(Criteria.of().eq("scopeKey", "scope-b"), scopeAParentId)).isEmpty();
        assertThat(service.scopedTreeCriteria(Criteria.of().eq("scopeKey", "scope-a"), scopeAParentId).getClauses())
                .extracting(clause -> clause.getField())
                .contains("scopeKey", "parentId");

        DemoOrganization invalidChild = new DemoOrganization("Invalid Child", scopeBParentId);
        invalidChild.setScopeKey("scope-a");
        assertThatThrownBy(() -> service.validateTreePlacementInScope(invalidChild,
                Criteria.of().eq("scopeKey", "scope-a"), "parent must match scope"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("parent must match scope");
        assertThat(service.children(Criteria.of().eq("scopeKey", "scope-a"), scopeAParentId))
                .extracting(DemoOrganization::getId)
                .containsExactly(scopeAChildId);
    }

    @Test
    void scopeHelpersShouldBuildCriteriaAndValidateSortScopeByFields() {
        ScopedDemoOrganizationService service = new ScopedDemoOrganizationService();
        DemoOrganization left = new DemoOrganization("Left", TreeAbility.ROOT_ID);
        left.setScopeKey("scope-a");
        DemoOrganization right = new DemoOrganization("Right", TreeAbility.ROOT_ID);
        right.setScopeKey("scope-a");
        DemoOrganization other = new DemoOrganization("Other", TreeAbility.ROOT_ID);
        other.setScopeKey("scope-b");

        assertThat(service.sortScope(left).getClauses())
                .extracting(clause -> clause.getField())
                .contains("scopeKey", "parentId");
        service.validateSortScope(left, right);
        assertThatThrownBy(() -> service.validateSortScope(left, other))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same scope");
    }

    @Test
    void treeAbilityShouldMoveRecordsInsideScopedTree() {
        ScopedDemoOrganizationService service = new ScopedDemoOrganizationService();
        DemoOrganization firstParent = scopedOrganization("First Parent", TreeAbility.ROOT_ID, "scope-a");
        DemoOrganization secondParent = scopedOrganization("Second Parent", TreeAbility.ROOT_ID, "scope-a");
        DemoOrganization otherParent = scopedOrganization("Other Parent", TreeAbility.ROOT_ID, "scope-b");
        String firstParentId = service.insert(firstParent);
        String secondParentId = service.insert(secondParent);
        String otherParentId = service.insert(otherParent);
        String firstChildId = service.insert(scopedOrganization("First Child", firstParentId, "scope-a"));
        String secondChildId = service.insert(scopedOrganization("Second Child", secondParentId, "scope-a"));
        String otherChildId = service.insert(scopedOrganization("Other Child", otherParentId, "scope-b"));

        service.moveInTree(Criteria.of().eq("scopeKey", "scope-a"), firstChildId, secondChildId, null, secondParentId);

        assertThat(service.select(firstChildId).getParentId()).isEqualTo(secondParentId);
        assertThat(service.children(Criteria.of().eq("scopeKey", "scope-a"), secondParentId)
                        .stream()
                        .map(DemoOrganization::getId))
                .containsExactly(secondChildId, firstChildId);
        assertThatThrownBy(() -> service.moveInTree(Criteria.of().eq("scopeKey", "scope-a"),
                firstChildId, otherChildId, null, secondParentId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("neighbor must belong to target parent");
    }

    @Test
    void standardBusinessServiceShouldRouteSharedMutationHooks() {
        StandardDemoBusinessService service = new StandardDemoBusinessService();
        DemoPlainRecord record = new DemoPlainRecord("  alpha  ");

        String id = service.insert(record);

        assertThat(record.getTitle()).isEqualTo("alpha");
        assertThat(service.hooks).containsExactly("normalize", "save", "insert");

        service.hooks.clear();
        DemoPlainRecord update = new DemoPlainRecord("  beta  ");
        update.setId(id);
        update.setVersion(record.getVersion());
        service.update(update);

        assertThat(update.getTitle()).isEqualTo("beta");
        assertThat(service.hooks).containsExactly("normalize", "save", "update");
    }

    @Test
    void treeAbilityShouldHideSoftDeletedChildrenAndDescendants() {
        DemoOrganizationService service = new DemoOrganizationService();
        String parentId = service.insert(new DemoOrganization("Parent", TreeAbility.ROOT_ID));
        String activeChildId = service.insert(new DemoOrganization("Active child", parentId));
        String deletedChildId = service.insert(new DemoOrganization("Deleted child", parentId));
        String activeGrandchildId = service.insert(new DemoOrganization("Active grandchild", deletedChildId));
        service.delete(deletedChildId);

        assertThat(service.children(parentId))
                .extracting(DemoOrganization::getId)
                .containsExactly(activeChildId);
        assertThat(service.descendantIds(parentId)).containsExactly(activeChildId);
        assertThat(service.children(deletedChildId)).isEmpty();
        assertThat(service.descendantIds(deletedChildId)).isEmpty();
        assertThat(service.select(activeGrandchildId)).isNotNull();
    }

    @Test
    void treeAbilityShouldRejectCycles() {
        DemoOrganizationService service = new DemoOrganizationService();
        String parentId = service.insert(new DemoOrganization("Parent", TreeAbility.ROOT_ID));
        DemoOrganization child = new DemoOrganization("Child", parentId);
        String childId = service.insert(child);

        DemoOrganization parent = service.select(parentId);
        parent.setParentId(childId);

        assertThatThrownBy(() -> service.update(parent))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void treeAbilityShouldRejectMissingParent() {
        DemoOrganizationService service = new DemoOrganizationService();

        assertThatThrownBy(() -> service.insert(new DemoOrganization("Orphan", "missing-parent")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("missing parent");
    }

    @Test
    void treeAbilityShouldRejectCorruptDescendantCycles() {
        DemoOrganizationService service = new DemoOrganizationService();
        DemoOrganization first = new DemoOrganization("First", null);
        DemoOrganization second = new DemoOrganization("Second", null);

        String firstId = service.insert(first);
        second.setParentId(firstId);
        String secondId = service.insert(second);
        first.setParentId(secondId);

        assertThatThrownBy(() -> service.descendantIds(firstId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Tree cycle");
    }

    @Test
    void sortAbilityShouldReorderAndMoveRecords() {
        DemoOrganizationService service = new DemoOrganizationService();
        String first = service.insert(new DemoOrganization("First", TreeAbility.ROOT_ID));
        String second = service.insert(new DemoOrganization("Second", TreeAbility.ROOT_ID));
        String third = service.insert(new DemoOrganization("Third", TreeAbility.ROOT_ID));

        assertThat(service.children(TreeAbility.ROOT_ID).stream().map(DemoOrganization::getId))
                .containsExactly(first, second, third);

        service.reorder(List.of(first, second, third));
        assertThat(service.children(TreeAbility.ROOT_ID).stream().map(DemoOrganization::getId))
                .containsExactly(first, second, third);

        service.moveBefore(third, first);

        assertThat(service.sortedList(Criteria.of()).stream().map(DemoOrganization::getId))
                .containsExactly(third, first, second);

        service.moveAfter(first, second);

        assertThat(service.sortedList(Criteria.of()).stream().map(DemoOrganization::getId))
                .containsExactly(third, second, first);

        String fourth = service.insert(new DemoOrganization("Fourth", TreeAbility.ROOT_ID));
        assertThat(service.children(TreeAbility.ROOT_ID).stream().map(DemoOrganization::getId))
                .containsExactly(third, second, first, fourth);
    }

    @Test
    void sortAbilityShouldNormalizeWhenMovingAfterNearIntegerLimit() {
        DemoOrganizationService service = new DemoOrganizationService();
        String first = service.insert(new DemoOrganization("First", TreeAbility.ROOT_ID));
        String second = service.insert(new DemoOrganization("Second", TreeAbility.ROOT_ID));
        DemoOrganization secondRecord = service.select(second);
        secondRecord.setSortOrder(Integer.MAX_VALUE - 10);
        service.update(secondRecord);

        service.moveAfter(first, second);

        assertThat(service.children(TreeAbility.ROOT_ID).stream().map(DemoOrganization::getId))
                .containsExactly(second, first);
        assertThat(service.children(TreeAbility.ROOT_ID).stream().map(DemoOrganization::getSortOrder))
                .allSatisfy(order -> assertThat(order).isPositive());
    }

    @Test
    void sortAbilityShouldKeepReorderScopeInsideCurrentTenant() {
        DemoOrganizationService service = new DemoOrganizationService();
        String tenantAFirst;
        String tenantASecond;
        String tenantBFirst;
        String tenantBSecond;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantAFirst = service.insert(new DemoOrganization("Tenant A First", TreeAbility.ROOT_ID));
            tenantASecond = service.insert(new DemoOrganization("Tenant A Second", TreeAbility.ROOT_ID));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            tenantBFirst = service.insert(new DemoOrganization("Tenant B First", TreeAbility.ROOT_ID));
            tenantBSecond = service.insert(new DemoOrganization("Tenant B Second", TreeAbility.ROOT_ID));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.reorder(List.of(tenantASecond, tenantAFirst));
            assertThat(service.sortedList(Criteria.of()).stream().map(DemoOrganization::getId))
                    .containsExactly(tenantASecond, tenantAFirst);
            assertThatThrownBy(() -> service.reorder(List.of(tenantAFirst, tenantBFirst)))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("missing record");
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            assertThat(service.sortedList(Criteria.of()).stream().map(DemoOrganization::getId))
                    .containsExactly(tenantBFirst, tenantBSecond);
            service.moveAfter(tenantBFirst, tenantBSecond);
            assertThat(service.sortedList(Criteria.of()).stream().map(DemoOrganization::getId))
                    .containsExactly(tenantBSecond, tenantBFirst);
        }
    }

    @Test
    void sortAbilityShouldKeepReorderScopeInsideActiveRows() {
        DemoOrganizationService service = new DemoOrganizationService();
        String first = service.insert(new DemoOrganization("First", TreeAbility.ROOT_ID));
        String second = service.insert(new DemoOrganization("Second", TreeAbility.ROOT_ID));
        String third = service.insert(new DemoOrganization("Third", TreeAbility.ROOT_ID));
        service.delete(second);

        service.reorder(List.of(third, first));

        assertThat(service.sortedList(Criteria.of()).stream().map(DemoOrganization::getId))
                .containsExactly(third, first);
        assertThatThrownBy(() -> service.moveBefore(second, first))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("missing record");
    }

    @Test
    void sortAbilityShouldRejectCrossParentTreeMove() {
        DemoOrganizationService service = new DemoOrganizationService();
        String firstParent = service.insert(new DemoOrganization("First Parent", TreeAbility.ROOT_ID));
        String secondParent = service.insert(new DemoOrganization("Second Parent", TreeAbility.ROOT_ID));
        String firstChild = service.insert(new DemoOrganization("First Child", firstParent));
        String secondChild = service.insert(new DemoOrganization("Second Child", secondParent));

        assertThatThrownBy(() -> service.moveBefore(firstChild, secondChild))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same parent");
    }

    @Test
    void treeAbilityShouldMoveRecordAcrossParentsWhenSortingInTree() {
        DemoOrganizationService service = new DemoOrganizationService();
        String firstParent = service.insert(new DemoOrganization("First Parent", TreeAbility.ROOT_ID));
        String secondParent = service.insert(new DemoOrganization("Second Parent", TreeAbility.ROOT_ID));
        String firstChild = service.insert(new DemoOrganization("First Child", firstParent));
        String secondChild = service.insert(new DemoOrganization("Second Child", secondParent));

        service.moveInTree(firstChild, secondChild, null, secondParent);

        assertThat(service.select(firstChild).getParentId()).isEqualTo(secondParent);
        assertThat(service.children(firstParent)).isEmpty();
        assertThat(service.children(secondParent).stream().map(DemoOrganization::getId))
                .containsExactly(secondChild, firstChild);
    }

    @Test
    void treeAbilityShouldRejectSelfNeighborWhenSortingInTree() {
        DemoOrganizationService service = new DemoOrganizationService();
        String parent = service.insert(new DemoOrganization("Parent", TreeAbility.ROOT_ID));
        String child = service.insert(new DemoOrganization("Child", parent));

        assertThatThrownBy(() -> service.moveInTree(child, child, null, parent))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("neighbor cannot be moving record");
        assertThatThrownBy(() -> service.moveInTree(child, null, child, parent))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("neighbor cannot be moving record");
    }

    @Test
    void sortAbilityShouldRejectDuplicateReorderIds() {
        DemoOrganizationService service = new DemoOrganizationService();
        String id = service.insert(new DemoOrganization("Duplicate", TreeAbility.ROOT_ID));

        assertThatThrownBy(() -> service.reorder(List.of(id, id)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void sortAbilityShouldRejectEmptyAndCrossParentReorder() {
        DemoOrganizationService service = new DemoOrganizationService();
        String firstParent = service.insert(new DemoOrganization("First Parent", TreeAbility.ROOT_ID));
        String secondParent = service.insert(new DemoOrganization("Second Parent", TreeAbility.ROOT_ID));
        String firstChild = service.insert(new DemoOrganization("First Child", firstParent));
        String secondChild = service.insert(new DemoOrganization("Second Child", secondParent));

        assertThatThrownBy(() -> service.reorder(List.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> service.reorder(List.of(firstChild, secondChild)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same parent");
    }

    @Test
    void sortAbilityShouldRejectPartialReorderScope() {
        DemoOrganizationService service = new DemoOrganizationService();
        String first = service.insert(new DemoOrganization("First", TreeAbility.ROOT_ID));
        String second = service.insert(new DemoOrganization("Second", TreeAbility.ROOT_ID));
        String third = service.insert(new DemoOrganization("Third", TreeAbility.ROOT_ID));

        assertThatThrownBy(() -> service.reorder(List.of(third, first)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("complete scope");

        assertThat(service.sortedList(Criteria.of()).stream().map(DemoOrganization::getId))
                .containsExactly(first, second, third);
    }

    @Test
    void referenceAbilityShouldResolveTitles() {
        DemoOrganizationService service = new DemoOrganizationService();
        String id = service.insert(new DemoOrganization("Reference Title", TreeAbility.ROOT_ID));
        String secondId = service.insert(new DemoOrganization("Second Title", TreeAbility.ROOT_ID));

        assertThat(service.title(id)).isEqualTo("Reference Title");
        assertThat(service.titles(java.util.List.of(secondId, id)))
                .containsExactly(
                        Map.entry(secondId, "Second Title"),
                        Map.entry(id, "Reference Title")
                );
        assertThat(service.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .containsExactly(
                        new ReferenceOption(id, "Reference Title"),
                        new ReferenceOption(secondId, "Second Title")
                );
    }

    @Test
    void referenceAbilityShouldHideSoftDeletedTargets() {
        DemoOrganizationService service = new DemoOrganizationService();
        String activeId = service.insert(new DemoOrganization("Active Reference", TreeAbility.ROOT_ID));
        String deletedId = service.insert(new DemoOrganization("Deleted Reference", TreeAbility.ROOT_ID));

        service.delete(deletedId);

        assertThat(service.title(deletedId)).isNull();
        assertThat(service.titles(List.of(deletedId, activeId)))
                .containsExactly(Map.entry(activeId, "Active Reference"));
        assertThat(service.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .containsExactly(new ReferenceOption(activeId, "Active Reference"));
    }

    @Test
    void referenceTitleShouldLoadTargetRecordAsRawData() {
        DemoCustomerService service = new DemoCustomerService();
        DemoCustomer customer = new DemoCustomer("Reference Title", "ACTIVE");
        String id = service.insert(customer);

        assertThat(service.title(id)).isEqualTo("Reference Title");
        assertThat(service.titles(List.of(id))).containsExactly(Map.entry(id, "Reference Title"));
        assertThat(service.projections(List.of(id), List.of("status")))
                .containsEntry(id, Map.of("status", "ACTIVE"));
        assertThat(service.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .containsExactly(new ReferenceOption(id, "Reference Title"));
        assertThat(service.afterSelectCount()).isZero();

        service.select(id);
        assertThat(service.afterSelectCount()).isEqualTo(1);
    }

    @Test
    void referenceAbilityShouldPreferDeclaredTitleField() {
        DemoCustomTitleRecordService service = new DemoCustomTitleRecordService();
        String id = service.insert(new DemoCustomTitleRecord("Raw title", "Display title"));

        assertThat(service.title(id)).isEqualTo("Display title");
        assertThat(service.projections(java.util.List.of(id), java.util.List.of("title", "displayName")))
                .containsEntry(id, Map.of("title", "Raw title", "displayName", "Display title"));
        assertThat(service.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .containsExactly(new ReferenceOption(id, "Display title"));
    }

    @Test
    void referenceAbilityShouldAllowNullProjectionValues() {
        DemoCustomTitleRecordService service = new DemoCustomTitleRecordService();
        String id = service.insert(new DemoCustomTitleRecord("Raw title", null));

        assertThat(service.projections(java.util.List.of(id), java.util.List.of("displayName")).get(id))
                .containsEntry("displayName", null);
    }

    @Test
    void referenceAbilityShouldFallbackToTitledCapableWhenTitleFieldIsUndeclared() {
        DemoUndeclaredTitleRecordService service = new DemoUndeclaredTitleRecordService();
        String id = service.insert(new DemoUndeclaredTitleRecord("Undeclared title"));

        assertThat(TitleFieldResolver.isTitledCapableWithoutTitleField(DemoUndeclaredTitleRecord.class)).isTrue();
        assertThat(service.title(id)).isEqualTo("Undeclared title");
        assertThat(service.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .containsExactly(new ReferenceOption(id, "Undeclared title"));
    }

    @Test
    void referenceAbilityShouldKeepTitlesAndOptionsInsideCurrentTenant() {
        DemoOrganizationService service = new DemoOrganizationService();
        String tenantAId;
        String tenantBId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantAId = service.insert(new DemoOrganization("Tenant A", TreeAbility.ROOT_ID));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            tenantBId = service.insert(new DemoOrganization("Tenant B", TreeAbility.ROOT_ID));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.title(tenantAId)).isEqualTo("Tenant A");
            assertThat(service.title(tenantBId)).isNull();
            assertThat(service.titles(List.of(tenantBId, tenantAId)))
                    .containsExactly(Map.entry(tenantAId, "Tenant A"));
            assertThat(service.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                    .containsExactly(new ReferenceOption(tenantAId, "Tenant A"));
        }
    }

    @Test
    void referenceAbilityShouldHideDeletedTitlesInBatch() {
        DemoOrganizationService service = new DemoOrganizationService();
        String activeId = service.insert(new DemoOrganization("Active", TreeAbility.ROOT_ID));
        String deletedId = service.insert(new DemoOrganization("Deleted", TreeAbility.ROOT_ID));
        service.delete(deletedId);

        assertThat(service.titles(List.of(deletedId, activeId)))
                .containsExactly(Map.entry(activeId, "Active"));
    }

    @Test
    void referenceAbilityShouldHideSoftDeletedRowsAcrossReadShapes() {
        DemoOrganizationService service = new DemoOrganizationService();
        String activeId = service.insert(new DemoOrganization("Active", TreeAbility.ROOT_ID));
        String deletedId = service.insert(new DemoOrganization("Deleted", TreeAbility.ROOT_ID));
        service.delete(deletedId);

        assertThat(service.title(deletedId)).isNull();
        assertThat(service.titles(List.of(deletedId, activeId)))
                .containsExactly(Map.entry(activeId, "Active"));
        assertThat(service.projections(List.of(deletedId, activeId), List.of("title")))
                .containsExactly(Map.entry(activeId, Map.of("title", "Active")));
        assertThat(service.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .containsExactly(new ReferenceOption(activeId, "Active"));
    }

    @Test
    void pageQueryShouldHideSoftDeletedRows() {
        DemoOrganizationService service = new DemoOrganizationService();
        String activeId = service.insert(new DemoOrganization("Active", TreeAbility.ROOT_ID));
        DemoOrganization nullDeleted = new DemoOrganization("Null Deleted", TreeAbility.ROOT_ID);
        String nullDeletedId = service.insert(nullDeleted);
        nullDeleted.setDeleted(null);
        String deletedId = service.insert(new DemoOrganization("Deleted", TreeAbility.ROOT_ID));
        service.delete(deletedId);

        assertThat(service.pageQuery(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .extracting(DemoOrganization::getId)
                .containsExactly(activeId, nullDeletedId);
    }

    @Test
    void listReadsShouldBatchPopulateReferenceLoadsWithoutLoadingInverseCollections() {
        StandardDemoBusinessService service = new StandardDemoBusinessService();
        String firstId = service.insert(new DemoPlainRecord("First"));
        String secondId = service.insert(new DemoPlainRecord("Second"));
        List<List<String>> batches = new ArrayList<>();
        PlatformAbilityRuntime.configureReferenceLoadResolver(new ReferenceLoadResolver() {
            @Override
            public void populate(CrudAbility<?> ability, EntityContract entity) {
                throw new AssertionError("list reads must use the batch reference-load entry point");
            }

            @Override
            public void populateAll(CrudAbility<?> ability, java.util.Collection<? extends EntityContract> entities) {
                batches.add(entities.stream().map(EntityContract::getId).toList());
            }
        });
        PlatformAbilityRuntime.configureReferencedByResolver((ability, entity) -> {
            throw new AssertionError("ordinary list reads must not populate inverse collections");
        });

        assertThat(service.list(Criteria.of())).extracting(DemoPlainRecord::getId)
                .containsExactly(firstId, secondId);
        assertThat(service.list(Criteria.of(), PageRequest.of(1, 10))).extracting(DemoPlainRecord::getId)
                .containsExactly(firstId, secondId);
        assertThat(service.pageQuery(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .extracting(DemoPlainRecord::getId)
                .containsExactly(firstId, secondId);

        assertThat(batches).containsExactly(List.of(firstId, secondId), List.of(firstId, secondId),
                List.of(firstId, secondId));
    }

    @Test
    void abilityQueriesShouldNotMutateCallerCriteria() {
        DemoOrganizationService service = new DemoOrganizationService();
        Criteria criteria = Criteria.of().eq("parentId", TreeAbility.ROOT_ID);

        service.pageQuery(criteria, PageRequest.of(1, 10));
        service.count(criteria);
        service.sortedList(criteria);

        assertThat(criteria.getClauses())
                .extracting(clause -> clause.getField() + ":" + clause.getOperator())
                .containsExactly("parentId:EQ");
    }

    private static final class NoNoArgCachedRecord extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
        private final String title;

        private NoNoArgCachedRecord(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    private static final class NoNoArgCachedRecordService extends AbstractAbilityService<NoNoArgCachedRecord> implements
            CacheAbility<NoNoArgCachedRecord> {
        private NoNoArgCachedRecordService() {
            super("demo.noNoArgCachedRecord", NoNoArgCachedRecord.class, new InMemoryBaseDao<>());
        }
    }

    private static final class NoModelChildrenService implements CrudAbility<DemoInvoice>, ChildrenAbility<DemoInvoice> {
        private final InMemoryBaseDao<DemoInvoice> dao = new InMemoryBaseDao<>();

        @Override
        public BaseDao<DemoInvoice, String> getDao() {
            return dao;
        }

        @Override
        public String getModuleAlias() {
            return "demo.noModelChildren";
        }
    }

    private static final class SingleChildInvoice extends StandardEntity {
        private String title;
        @Children
        private List<SingleChildInvoiceLine> lines;

        private SingleChildInvoice(String title, List<SingleChildInvoiceLine> lines) {
            this.title = title;
            this.lines = lines;
        }

        public List<SingleChildInvoiceLine> getLines() {
            return lines;
        }

        public void setLines(List<SingleChildInvoiceLine> lines) {
            this.lines = lines;
        }
    }

    private static final class SingleChildInvoiceService extends AbstractAbilityService<SingleChildInvoice> implements
            SoftDeleteAbility<SingleChildInvoice>,
            ChildrenAbility<SingleChildInvoice> {
        private final SingleChildInvoiceLineService lineService = new SingleChildInvoiceLineService();

        private SingleChildInvoiceService() {
            super("demo.singleChildInvoice", SingleChildInvoice.class, new InMemoryBaseDao<>());
        }

        @Override
        public List<ChildRelation<? extends EntityContract, SingleChildInvoice>> childRelations() {
            return List.of(childRelation(lineService));
        }

        @Override
        public boolean usesAutomaticChildRelations() {
            return false;
        }
    }

    private static final class SingleChildInvoiceLine extends StandardEntity {
        private String title;
        @ChildOf
        @ReferenceTo(moduleAlias = "demo", entityAlias = "singleChildInvoice")
        private String invoiceId;

        private SingleChildInvoiceLine(String title) {
            this.title = title;
        }

        public String getInvoiceId() {
            return invoiceId;
        }

        public String getTitle() {
            return title;
        }
    }

    private static final class SingleChildInvoiceLineService extends AbstractAbilityService<SingleChildInvoiceLine> implements
            ChildAbility<SingleChildInvoiceLine> {
        private SingleChildInvoiceLineService() {
            super("demo.singleChildInvoiceLine", SingleChildInvoiceLine.class, new InMemoryBaseDao<>());
        }
    }

    private static class StaticReferenceBaseRecord extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
    }

    private static class PlainReferenceRecord extends StandardEntity {
        @ReferenceTo(moduleAlias = "demo", entityAlias = "customer")
        private final String customerId;

        protected PlainReferenceRecord(String customerId) {
            this.customerId = customerId;
        }
    }

    private static final class PlainReferenceRecordService extends AbstractAbilityService<PlainReferenceRecord> {
        private PlainReferenceRecordService() {
            super("demo.plain-reference", PlainReferenceRecord.class, new InMemoryBaseDao<>());
        }
    }

    private static final class ReferencingPlainRecord extends PlainReferenceRecord {
        private ReferencingPlainRecord(String customerId) {
            super(customerId);
        }
    }

    private static final class ReferencingPlainRecordService extends AbstractAbilityService<ReferencingPlainRecord>
            implements ReferencerAbility<ReferencingPlainRecord> {
        private ReferencingPlainRecordService() {
            super("demo.referencing-plain-reference", ReferencingPlainRecord.class, new InMemoryBaseDao<>());
        }
    }

    private static final class SoftReferenceRecordService extends AbstractAbilityService<PlainReferenceRecord>
            implements SoftDeleteAbility<PlainReferenceRecord> {
        private SoftReferenceRecordService() {
            super("demo.soft-reference", PlainReferenceRecord.class, new InMemoryBaseDao<>());
        }
    }

    private static final class StaticReferenceProxyRecord extends StaticReferenceBaseRecord {
        @ReferenceTo(moduleAlias = "demo", entityAlias = "customer")
        private final String customerId;

        private StaticReferenceProxyRecord(String customerId) {
            this.customerId = customerId;
        }
    }

    private static final class StaticReferenceBaseService extends AbstractAbilityService<StaticReferenceBaseRecord> implements
            ReferencerAbility<StaticReferenceBaseRecord> {
        private StaticReferenceBaseService() {
            super("demo.staticReferenceBase", StaticReferenceBaseRecord.class, new InMemoryBaseDao<>());
        }
    }

    private void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static DemoOrganization scopedOrganization(String title, String parentId, String scopeKey) {
        DemoOrganization organization = new DemoOrganization(title, parentId);
        organization.setScopeKey(scopeKey);
        return organization;
    }

    private static final class ScopedDemoOrganizationService extends AbstractAbilityService<DemoOrganization> implements
            SoftDeleteAbility<DemoOrganization>,
            TreeAbility<DemoOrganization> {
        private ScopedDemoOrganizationService() {
            super("demo.scopedOrganization", DemoOrganization.class, new InMemoryBaseDao<>());
        }

        @Override
        public Criteria sortScope(DemoOrganization organization) {
            return scopedTreeCriteria(organization, "scopeKey");
        }

        @Override
        public void validateSortScope(DemoOrganization left, DemoOrganization right) {
            validateTreeSortScopeByFields(left, right,
                    "Scoped organization sort can only move records within the same scope", "scopeKey");
        }
    }

    private static final class StandardDemoBusinessService extends StandardBusinessService<DemoPlainRecord> {
        private final List<String> hooks = new ArrayList<>();

        private StandardDemoBusinessService() {
            super("demo.standardBusiness", DemoPlainRecord.class, new InMemoryBaseDao<>());
        }

        @Override
        public void normalizeBeforeMutation(DemoPlainRecord record) {
            hooks.add("normalize");
            record.setTitle(record.getTitle().trim());
        }

        @Override
        protected void validateBeforeSave(DemoPlainRecord record) {
            hooks.add("save");
            if (record.getTitle().isBlank()) {
                throw new PlatformException("title must not be blank");
            }
        }

        @Override
        protected void validateBeforeInsert(DemoPlainRecord record) {
            hooks.add("insert");
        }

        @Override
        protected void validateBeforeUpdate(DemoPlainRecord record) {
            hooks.add("update");
        }
    }

    private static final class FailingAfterChangedService extends AbstractAbilityService<DemoPlainRecord> {
        private boolean failAfterChanged;

        private FailingAfterChangedService(boolean failAfterChanged) {
            super("demo.failingAfterChanged", DemoPlainRecord.class, new InMemoryBaseDao<>());
            this.failAfterChanged = failAfterChanged;
        }

        @Override
        public void afterChanged(DemoPlainRecord entity) {
            if (failAfterChanged) {
                throw new PlatformException("after changed failed");
            }
        }
    }

    private static final class FailingFieldProtectionService extends AbstractAbilityService<DemoPlainRecord> implements
            FieldProtectionAbility<DemoPlainRecord> {
        private FailingFieldProtectionService() {
            super("demo.failingFieldProtection", DemoPlainRecord.class, new InMemoryBaseDao<>());
        }

        @Override
        public FieldProtectionMutation protectFieldsForStorage(DemoPlainRecord entity) {
            return () -> {
                throw new PlatformException("protected field restore failed");
            };
        }
    }
}
