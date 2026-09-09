package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageReferenceFieldCatalogServiceTest {
    private final DynamicRecordService records = mock(DynamicRecordService.class);

    @AfterEach void resetResolver() { PlatformAbilityRuntime.resetReferenceTargetResolver(); }

    @Test
    void listsDynamicMainAliasAndStaticTargetThroughDeclaredSafePaths() {
        ModuleDefinition purchase = ModuleDefinition.builder("education.e2e_purchase", "采购单")
                .entities(List.of(new EntityDefinition("purchase_root", "purchase", "采购单", List.of(
                        FieldDefinition.string("supplierId", "供应商"),
                        FieldDefinition.string("userId", "创建用户"),
                        FieldDefinition.string("secretSupplierId", "受保护供应商")
                                .protection(new FieldProtectionDefinition(FieldEncryptionMode.ENCRYPTED, null, null)),
                        FieldDefinition.string("id", "技术主键")))))
                .references(List.of(
                        EntityReferenceDefinition.to("purchase_root", "supplierId",
                                ReferenceTarget.of("supply.supplier", "supplier_root")),
                        EntityReferenceDefinition.to("purchase_root", "userId", ReferenceTarget.of("iam", "user")),
                        EntityReferenceDefinition.to("purchase_root", "secretSupplierId",
                                ReferenceTarget.of("supply.supplier", "supplier_root"))))
                .mainEntityAlias("purchase_root").build();
        ModuleDefinition supplier = new ModuleDefinition("supply.supplier", "供应商", List.of(
                new EntityDefinition("supplier_root", "supplier", "供应商", List.of(
                        FieldDefinition.string("title", "供应商名称"),
                        FieldDefinition.string("secret", "保密名称")
                                .protection(new FieldProtectionDefinition(FieldEncryptionMode.ENCRYPTED, null, null)),
                        FieldDefinition.string("deleted", "已删除")))));
        when(records.describe("education.e2e_purchase")).thenReturn(DynamicModuleDescriptor.from(purchase));
        when(records.describe("supply.supplier")).thenReturn(DynamicModuleDescriptor.from(supplier));
        when(records.describe("iam.user")).thenThrow(new ModuleDefinitionException("not dynamic"));
        PlatformAbilityRuntime.configureReferenceTargetResolver(resolver(purchase));
        StaticModuleDefinition staticUser = StaticModuleDefinition.builder("iam", "iam.user", "用户")
                .entities(List.of(new EntityDefinition("user", "iam_user", "用户", List.of(
                        FieldDefinition.string("title", "用户名称"), FieldDefinition.string("deleted", "已删除")))))
                .build();
        PageReferenceFieldCatalogService catalog = new PageReferenceFieldCatalogService(records,
                new StaticModuleDefinitionCatalog(List.of(staticUser)));

        PageReferenceFieldCatalog root = catalog.list("education.e2e_purchase", null);
        assertThat(root.fields()).extracting(PageReferenceFieldCatalog.Field::name)
                .contains("supplierId", "userId").doesNotContain("secretSupplierId", "id");
        assertThat(root.fields()).filteredOn(field -> field.name().equals("supplierId")).singleElement()
                .satisfies(field -> {
                    assertThat(field.referenceModuleAlias()).isEqualTo("supply.supplier");
                    assertThat(field.expandable()).isTrue();
                    assertThat(field.readOnly()).isFalse();
                });
        assertThat(catalog.list("education.e2e_purchase", "supplierId").fields())
                .extracting(PageReferenceFieldCatalog.Field::name)
                .contains("supplierId.title").doesNotContain("supplierId.secret", "supplierId.deleted");
        assertThat(catalog.list("education.e2e_purchase", "supplierId").fields())
                .filteredOn(field -> field.name().equals("supplierId.title")).singleElement()
                .satisfies(field -> { assertThat(field.label()).isEqualTo("供应商名称"); assertThat(field.readOnly()).isTrue(); });
        assertThat(catalog.list("education.e2e_purchase", "userId").fields())
                .extracting(PageReferenceFieldCatalog.Field::name).containsExactly("userId.title");
        assertThatThrownBy(() -> catalog.list("education.e2e_purchase", "secretSupplierId"))
                .hasMessageContaining("protected");
        assertThatThrownBy(() -> catalog.title("education.e2e_purchase", "supplierId.secret"))
                .hasMessageContaining("protected");
    }

    @Test
    void stopsExpansionAtConfiguredMaximumDepthAndRejectsMany() {
        ModuleDefinition module = ModuleDefinition.builder("sales.order", "订单")
                .entities(List.of(new EntityDefinition("order_main", "orders", "订单", List.of(
                        FieldDefinition.string("supplierId", "供应商"), FieldDefinition.string("tagIds", "标签")))))
                .references(List.of(EntityReferenceDefinition.to("order_main", "supplierId", ReferenceTarget.of("supply", "supplier")),
                        EntityReferenceDefinition.to("order_main", "tagIds", ReferenceTarget.of("supply", "supplier")).many()))
                .mainEntityAlias("order_main").build();
        when(records.describe("sales.order")).thenReturn(DynamicModuleDescriptor.from(module));
        PlatformAbilityRuntime.configureReferenceTargetResolver(resolver(module));
        PageReferenceFieldCatalogService catalog = new PageReferenceFieldCatalogService(records,
                new StaticModuleDefinitionCatalog(List.of()));

        assertThat(catalog.list("sales.order", null).fields()).filteredOn(field -> field.name().equals("tagIds"))
                .singleElement().satisfies(field -> assertThat(field.expandable()).isFalse());
        assertThatThrownBy(() -> catalog.list("sales.order", "tagIds")).hasMessageContaining("ONE");
        assertThatThrownBy(() -> catalog.list("sales.order", "a.b.c.d.e.f.g"))
                .hasMessageContaining("exceeds");
    }

    private static net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver resolver(ModuleDefinition module) {
        return new net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver() {
            @Override public Optional<net.ximatai.muyun.spring.ability.reference.ReferenceAbility<?>> resolve(ReferenceTarget target) {
                return Optional.empty();
            }
            @Override public Optional<ReferencePlan> referencePlan(ReferenceTarget target, String field) {
                return module.references().stream()
                        .filter(reference -> module.moduleAlias().equals(target.moduleAlias()))
                        .filter(reference -> reference.sourceEntityAlias().equals(target.entityAlias()))
                        .filter(reference -> reference.sourceField().equals(field)).map(EntityReferenceDefinition::plan).findFirst();
            }
        };
    }
}
