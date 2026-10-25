package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.child.AggregateChildMutationContext;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileReferenceUploadRelationTest {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final String FILE_ID = "uploaded-file";

    @Test
    void staticChildUsesTheDeclaringParentModuleAndRelationRatherThanItsOwnServiceAlias() {
        var uploads = uploads();
        var child = new ItemService();
        var parent = new OrderService(child);
        var incoming = item();
        var order = new Order();
        order.items = List.of(incoming);
        try (var tenant = TenantContext.use("tenant-a");
             var user = CurrentUserContext.use(CurrentUser.tenantUser("uploader", "Uploader", "tenant-a"))) {
            register(uploads, "sales.order", "items", "fileId", "item-1");
            try (var aggregate = AggregateChildMutationContext.open(parent, null, order)) {
                assertThatCode(() -> uploads.prepareValues(child, null, incoming)).doesNotThrowAnyException();
            }
            // The same payload cannot reuse a relation admission through a detached child write.
            assertThatThrownBy(() -> uploads.prepareValues(child, null, incoming))
                    .isInstanceOf(PlatformAccessDeniedException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "sales.order, other_items, fileId, item-1",
            "sales.order, items, otherFileId, item-1",
            "sales.order, items, fileId, other-item",
            "sales.other_order, items, fileId, item-1"
    })
    void staticChildRejectsRegistrationsForAnotherDestination(String module, String relation, String field, String record) {
        var uploads = uploads();
        var child = new ItemService();
        var parent = new OrderService(child);
        var incoming = item();
        var order = new Order();
        order.items = List.of(incoming);
        try (var tenant = TenantContext.use("tenant-a");
             var user = CurrentUserContext.use(CurrentUser.tenantUser("uploader", "Uploader", "tenant-a"))) {
            register(uploads, module, relation, field, record);
            try (var aggregate = AggregateChildMutationContext.open(parent, null, order)) {
                assertThatThrownBy(() -> uploads.prepareValues(child, null, incoming))
                        .isInstanceOf(PlatformAccessDeniedException.class);
            }
        }
    }

    @Test
    void dynamicChildrenSharingAJavaModelRemainIsolatedByTheirActualDeclaringRelation() {
        var uploads = uploads();
        var item = dynamicItem("item");
        var note = dynamicItem("note");
        var items = new DynamicItemService();
        var notes = new DynamicItemService();
        var parent = new DynamicOrderService(items, notes);
        var order = new DynamicRecord(new EntityDefinition("order", "test_order", "Order", List.of()))
                .setChildren("items", List.of(item)).setChildren("notes", List.of(note));
        try (var tenant = TenantContext.use("tenant-a");
             var user = CurrentUserContext.use(CurrentUser.tenantUser("uploader", "Uploader", "tenant-a"))) {
            register(uploads, "sales.dynamic_order", "items", "fileId", "item-1");
            try (var aggregate = AggregateChildMutationContext.open(parent, null, order)) {
                assertThatCode(() -> uploads.prepareValues(items, null, item)).doesNotThrowAnyException();
                // Both services have the same alias/model and both rows deliberately have the same id.
                // Only the actual incoming row's declared relation distinguishes their admissions.
                assertThatThrownBy(() -> uploads.prepareValues(notes, null, note))
                        .isInstanceOf(PlatformAccessDeniedException.class);
            }
        }
    }

    private static FileReferenceUploadService uploads() {
        var client = mock(FileTransferClient.class);
        when(client.readUploadFacts(FILE_ID)).thenReturn(new FileTransferUploadFacts(
                new FileTransferFileMetadata(FILE_ID, "item.pdf", "pdf", "application/pdf", 3,
                        "sha", "ACTIVE", true, NOW, null, null), "uploader", "tenant-a"));
        var beans = new StaticListableBeanFactory();
        beans.addBean("client", client);
        return new FileReferenceUploadService(new UploadDao(), mock(FileReferenceUploadClaimDao.class),
                beans.getBeanProvider(ManagedFileAssetService.class), beans.getBeanProvider(FileTransferClient.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void register(FileReferenceUploadService uploads, String module, String relation, String field, String record) {
        String ticket = uploads.registerTicket("tenant-a", new FileReferenceUploadRequest(module, relation, field,
                        Map.of("id", record), new FileReferenceUploadFile("item.pdf", "application/pdf", 3),
                        FileReferenceUploadIntent.CREATE),
                new FileTransferAccess(FileTransferOperation.UPLOAD, null, "issued-token", "http://files/upload", NOW.plusSeconds(600)));
        uploads.bindUploadedFile(module, ticket, FILE_ID);
    }

    private static Item item() {
        var item = new Item();
        item.setId("item-1");
        item.setTenantId("tenant-a");
        item.fileId = FILE_ID;
        return item;
    }

    private static DynamicRecord dynamicItem(String entityAlias) {
        var definition = new EntityDefinition(entityAlias, "test_" + entityAlias, entityAlias,
                List.of(FieldDefinition.string("fileId", "File").column("file_id")))
                .withFileReferences(Map.of("fileId", new FileReferenceDefinition(Set.of(), null, 1, Map.of())));
        var item = new DynamicRecord(definition).setValue("fileId", FILE_ID);
        item.setId("item-1");
        item.setTenantId("tenant-a");
        return item;
    }

    private static class UploadDao extends TestMemoryDao<FileReferenceUpload> implements FileReferenceUploadDao { }

    private static class Order extends StandardEntity {
        private List<Item> items;
    }

    private static class Item extends StandardEntity {
        @FileReference private String fileId;
    }

    private static class ItemService extends AbstractAbilityService<Item> implements ChildAbility<Item> {
        private ItemService() { super("sales.order_item", Item.class, new TestMemoryDao<>()); }
    }

    private static class OrderService extends AbstractAbilityService<Order> implements ChildrenAbility<Order> {
        private final ItemService child;
        private OrderService(ItemService child) {
            super("sales.order", Order.class, new TestMemoryDao<>());
            this.child = child;
        }
        @Override public List<ChildRelation<? extends EntityContract, Order>> childRelations() {
            return List.of(new ChildRelation<>("items", child, (item, parentId) -> { }, "orderId", order -> order.items));
        }
    }

    private static class DynamicItemService extends AbstractAbilityService<DynamicRecord> implements ChildAbility<DynamicRecord> {
        private DynamicItemService() { super("sales.dynamic_order", DynamicRecord.class, new TestMemoryDao<>()); }
    }

    private static class DynamicOrderService extends AbstractAbilityService<DynamicRecord> implements ChildrenAbility<DynamicRecord> {
        private final DynamicItemService items;
        private final DynamicItemService notes;
        private DynamicOrderService(DynamicItemService items, DynamicItemService notes) {
            super("sales.dynamic_order", DynamicRecord.class, new TestMemoryDao<>());
            this.items = items;
            this.notes = notes;
        }
        @Override public List<ChildRelation<? extends EntityContract, DynamicRecord>> childRelations() {
            return List.of(
                    new ChildRelation<>("items", items, (item, parentId) -> { }, "orderId", order -> order.getChildren("items")),
                    new ChildRelation<>("notes", notes, (note, parentId) -> { }, "orderId", order -> order.getChildren("notes")));
        }
    }
}
