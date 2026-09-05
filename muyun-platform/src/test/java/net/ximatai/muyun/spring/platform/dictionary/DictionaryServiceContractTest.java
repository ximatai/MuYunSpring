package net.ximatai.muyun.spring.platform.dictionary;

import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.common.option.OptionQuery;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.option.OptionSource;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class DictionaryServiceContractTest {
    private final TestMemoryDao<DictionaryCategory> categoryDao = new TestMemoryDao<>();
    private final TestMemoryDao<DictionaryItem> itemDao = new TestMemoryDao<>();
    private final DictionaryCategoryService categoryService = new DictionaryCategoryService(categoryDao);
    private final DictionaryItemService itemService = new DictionaryItemService(itemDao, categoryService);

    @Test
    void shouldCreateApplicationScopedCategoryTree() {
        String folderId = categoryService.insert(category("crm", "base", DictionaryCategoryKind.FOLDER, TreeAbility.ROOT_ID));
        String categoryId = categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, folderId));

        assertThat(categoryService.rootCategories("crm")).extracting(DictionaryCategory::getAlias).containsExactly("base");
        assertThat(categoryService.children("crm", folderId)).extracting(DictionaryCategory::getId).containsExactly(categoryId);
        assertThatThrownBy(() -> categoryService.children(TreeAbility.ROOT_ID))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("rootCategories");
    }

    @Test
    void shouldRejectCategoryUnderDictionaryCategory() {
        String dictionaryId = categoryService.insert(category("crm", "customer_status",
                DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));

        assertThatThrownBy(() -> categoryService.insert(category("crm", "invalid_child",
                DictionaryCategoryKind.FOLDER, dictionaryId)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("parent must be a folder");
    }

    @Test
    void shouldRejectMovingCategoryUnderDictionaryCategory() {
        String dictionaryId = categoryService.insert(category("crm", "customer_status",
                DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String folderId = categoryService.insert(category("crm", "base",
                DictionaryCategoryKind.FOLDER, TreeAbility.ROOT_ID));

        assertThatThrownBy(() -> categoryService.moveInTree(folderId, null, null, dictionaryId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("parent must be a folder");
    }

    @Test
    void shouldRejectDuplicateCategoryAliasWithinApplication() {
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));

        assertThatThrownBy(() -> categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unique");
        assertThat(categoryService.insert(category("sales", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID)))
                .isNotBlank();
    }

    @Test
    void shouldRejectCategoryTreeAcrossApplications() {
        String crmFolderId = categoryService.insert(category("crm", "base", DictionaryCategoryKind.FOLDER, TreeAbility.ROOT_ID));
        DictionaryCategory salesCategory = category("sales", "customer_status", DictionaryCategoryKind.DICTIONARY, crmFolderId);

        assertThatThrownBy(() -> categoryService.insert(salesCategory))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same application");
    }

    @Test
    void shouldRejectCategoryIdentityChanges() {
        String id = categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        DictionaryCategory changedAlias = category("crm", "customer_level", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID);
        changedAlias.setId(id);
        changedAlias.setVersion(0);

        assertThatThrownBy(() -> categoryService.update(changedAlias))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("alias");

        DictionaryCategory changedKind = category("crm", "customer_status", DictionaryCategoryKind.FOLDER, TreeAbility.ROOT_ID);
        changedKind.setId(id);
        changedKind.setVersion(0);
        assertThatThrownBy(() -> categoryService.update(changedKind))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void shouldCreateDictionaryItemsWithCodeAsBusinessValue() {
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String activeId = itemService.insert(item("crm", "customer_status", "active", TreeAbility.ROOT_ID));
        DictionaryItem frozen = item("crm", "customer_status", "frozen", activeId);
        String frozenId = itemService.insert(frozen);

        assertThat(itemService.resolveItem("crm", "customer_status", "active").getId()).isEqualTo(activeId);
        assertThat(itemService.rootItems("crm", "customer_status")).extracting(DictionaryItem::getCode).containsExactly("active");
        assertThat(itemService.children("crm", "customer_status", activeId)).extracting(DictionaryItem::getId).containsExactly(frozenId);
    }

    @Test
    void shouldAllowNumericDictionaryItemCodeAsBusinessValue() {
        categoryService.insert(category("crm", "gender", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));

        String maleId = itemService.insert(item("crm", "gender", "1", TreeAbility.ROOT_ID));

        assertThat(itemService.resolveItem("crm", "gender", "1").getId()).isEqualTo(maleId);
        assertThat(itemService.rootItems("crm", "gender"))
                .extracting(DictionaryItem::getCode)
                .containsExactly("1");
    }

    @Test
    void shouldAllowIanaZoneIdsOnlyForThePlatformTimeZoneDictionary() {
        categoryService.insert(category("platform", PlatformTimeZoneDictionaryInitialDataDeclarationProvider.CATEGORY_ALIAS,
                DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String itemId = itemService.insert(item("platform", PlatformTimeZoneDictionaryInitialDataDeclarationProvider.CATEGORY_ALIAS,
                "Asia/Shanghai", TreeAbility.ROOT_ID));

        assertThat(itemService.resolveItem("platform", PlatformTimeZoneDictionaryInitialDataDeclarationProvider.CATEGORY_ALIAS,
                "Asia/Shanghai").getId()).isEqualTo(itemId);
        categoryService.insert(category("crm", "region", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        assertThatThrownBy(() -> itemService.insert(item("crm", "region", "Asia/Shanghai", TreeAbility.ROOT_ID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dictionaryItemCode");
    }

    @Test
    void shouldResolveOnlyEnabledDictionaryItemForWriteValidation() {
        String categoryId = categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String activeId = itemService.insert(item("crm", "customer_status", "active", TreeAbility.ROOT_ID));
        String frozenId = itemService.insert(item("crm", "customer_status", "frozen", TreeAbility.ROOT_ID));

        itemService.disable(frozenId);

        assertThat(itemService.resolveEnabledItem("crm", "customer_status", "active").getId()).isEqualTo(activeId);
        assertThat(itemService.resolveItem("crm", "customer_status", "frozen")).isNotNull();
        assertThat(itemService.resolveEnabledItem("crm", "customer_status", "frozen")).isNull();

        DictionaryCategory disabled = category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID);
        disabled.setId(categoryId);
        disabled.setVersion(0);
        disabled.setEnabled(false);
        categoryService.update(disabled);
        assertThatThrownBy(() -> itemService.resolveEnabledItem("crm", "customer_status", "active"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void shouldExposeDictionaryAsOptionSource() {
        categoryService.insert(category("crm", "area", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String chinaId = itemService.insert(item("crm", "area", "china", TreeAbility.ROOT_ID));
        String shanghaiId = itemService.insert(item("crm", "area", "shanghai", chinaId));
        String beijingId = itemService.insert(item("crm", "area", "beijing", chinaId));
        itemService.disable(beijingId);
        DictionaryOptionSource source = new DictionaryOptionSource("crm", "area", itemService);

        assertThat(source.binding().source()).isEqualTo("crm.area");
        assertThat(source.options()).extracting(OptionItem::code)
                .containsExactly("china", "shanghai");
        assertThat(source.options(OptionQuery.all().childrenOf("china"))).extracting(OptionItem::code)
                .containsExactly("shanghai", "beijing");
        assertThat(source.resolve("shanghai"))
                .extracting(OptionItem::code, OptionItem::title, OptionItem::enabled, OptionItem::parentCode)
                .containsExactly("shanghai", "shanghai", true, "china");
        assertThat(itemService.select(shanghaiId).getParentId()).isEqualTo(chinaId);
    }

    @Test
    void shouldResolveDictionaryOptionSourceByBinding() {
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        itemService.insert(item("crm", "customer_status", "active", TreeAbility.ROOT_ID));
        OptionSourceRegistry registry = new OptionSourceRegistry(List.of(new DictionaryOptionSourceProvider(itemService)));

        OptionSource source = registry.source(OptionBinding.dictionary("crm", "customer_status"));

        assertThat(source.options())
                .extracting(OptionItem::code, OptionItem::title, OptionItem::enabled)
                .containsExactly(tuple("active", "active", true));
        assertThat(source.resolve("active").code()).isEqualTo("active");
    }

    @Test
    void optionSourceShouldFallbackToGlobalDictionaryInTenantContext() {
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        itemService.insert(titledItem("crm", "customer_status", "active", "Active", TreeAbility.ROOT_ID));
        DictionaryOptionSource source = new DictionaryOptionSource("crm", "customer_status", itemService);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> itemService.listItems("crm", "customer_status", true))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("crm.customer_status");
            assertThat(source.options())
                    .extracting(OptionItem::code, OptionItem::title)
                    .containsExactly(tuple("active", "Active"));
            assertThat(source.resolve("active").title()).isEqualTo("Active");
        }
    }

    @Test
    void optionSourceResolveShouldKeepGlobalFallbackParentCode() {
        categoryService.insert(category("crm", "area", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String chinaId = itemService.insert(titledItem("crm", "area", "china", "China", TreeAbility.ROOT_ID));
        itemService.insert(titledItem("crm", "area", "shanghai", "Shanghai", chinaId));
        DictionaryOptionSource source = new DictionaryOptionSource("crm", "area", itemService);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(source.resolve("shanghai"))
                    .extracting(OptionItem::code, OptionItem::title, OptionItem::parentCode)
                    .containsExactly("shanghai", "Shanghai", "china");
        }
    }

    @Test
    void shouldValidateMultipleDictionaryCodes() {
        categoryService.insert(category("crm", "customer_tag", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        itemService.insert(item("crm", "customer_tag", "vip", TreeAbility.ROOT_ID));
        itemService.insert(item("crm", "customer_tag", "important", TreeAbility.ROOT_ID));
        DictionaryFieldValueValidator validator = new DictionaryFieldValueValidator(itemService);
        EntityDefinition entity = new EntityDefinition("customer", "crm_customer", "Customer", List.of(
                FieldDefinition.of("tags", FieldType.JSON, "Tags")
                        .dictionary("crm", "customer_tag", OptionSelectionMode.MULTIPLE)
                        .required()
        ));
        FieldDefinition field = entity.fields().getFirst();

        validator.validate("crm.customer", entity, field, List.of("vip", "important"));

        assertThatThrownBy(() -> validator.validate("crm.customer", entity, field, List.of("vip", "missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid dictionary code");
        assertThatThrownBy(() -> validator.validate("crm.customer", entity, field, "vip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires collection");
        assertThatThrownBy(() -> validator.validate("crm.customer", entity, field, List.of("vip", "vip")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate dictionary code");
        assertThatThrownBy(() -> validator.validate("crm.customer", entity, field, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void shouldRejectItemsForFolderCategoryAndDuplicateCodeWithinCategory() {
        categoryService.insert(category("crm", "base", DictionaryCategoryKind.FOLDER, TreeAbility.ROOT_ID));
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        itemService.insert(item("crm", "customer_status", "active", TreeAbility.ROOT_ID));

        assertThatThrownBy(() -> itemService.insert(item("crm", "base", "active", TreeAbility.ROOT_ID)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("DICTIONARY");
        assertThatThrownBy(() -> itemService.insert(item("crm", "customer_status", "active", TreeAbility.ROOT_ID)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void shouldRejectDuplicateItemTitleWithinCategory() {
        categoryService.insert(category("crm", "gender", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        itemService.insert(titledItem("crm", "gender", "1", "男", TreeAbility.ROOT_ID));

        assertThatThrownBy(() -> itemService.insert(titledItem("crm", "gender", "male", "男", TreeAbility.ROOT_ID)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("title");
        assertThat(itemService.insert(titledItem("crm", "customer_status", "male", "男", TreeAbility.ROOT_ID)))
                .isNotBlank();
    }

    @Test
    void shouldRejectItemTitleChangesToDuplicateWithinCategory() {
        categoryService.insert(category("crm", "gender", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        itemService.insert(titledItem("crm", "gender", "1", "男", TreeAbility.ROOT_ID));
        String femaleId = itemService.insert(titledItem("crm", "gender", "2", "女", TreeAbility.ROOT_ID));
        DictionaryItem changedTitle = titledItem("crm", "gender", "2", "男", TreeAbility.ROOT_ID);
        changedTitle.setId(femaleId);
        changedTitle.setVersion(0);

        assertThatThrownBy(() -> itemService.update(changedTitle))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("title");
    }

    @Test
    void shouldKeepItemCodeUniqueWithinWholeCategoryTree() {
        categoryService.insert(category("crm", "area", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String parentId = itemService.insert(item("crm", "area", "china", TreeAbility.ROOT_ID));

        assertThatThrownBy(() -> itemService.insert(item("crm", "area", "china", parentId)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void shouldRejectItemTreeAcrossCategories() {
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        categoryService.insert(category("crm", "customer_level", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String statusId = itemService.insert(item("crm", "customer_status", "active", TreeAbility.ROOT_ID));

        assertThatThrownBy(() -> itemService.insert(item("crm", "customer_level", "vip", statusId)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same category");
    }

    @Test
    void shouldRejectItemCodeChangesBecauseBusinessDataStoresCode() {
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String id = itemService.insert(item("crm", "customer_status", "active", TreeAbility.ROOT_ID));
        DictionaryItem changedCode = item("crm", "customer_status", "inactive", TreeAbility.ROOT_ID);
        changedCode.setId(id);
        changedCode.setVersion(0);

        assertThatThrownBy(() -> itemService.update(changedCode))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("code");

        categoryService.insert(category("crm", "customer_level", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        DictionaryItem changedCategory = item("crm", "customer_level", "active", TreeAbility.ROOT_ID);
        changedCategory.setId(id);
        changedCategory.setVersion(0);
        assertThatThrownBy(() -> itemService.update(changedCategory))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("category");
    }

    @Test
    void shouldIsolateDictionaryByTenantScope() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
            itemService.insert(item("crm", "customer_status", "active", TreeAbility.ROOT_ID));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            assertThat(categoryService.rootCategories("crm")).isEmpty();
            assertThatThrownBy(() -> itemService.resolveItem("crm", "customer_status", "active"))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("existing category");
        }
    }

    @Test
    void shouldReorderItemsWithinSameCategoryAndParent() {
        categoryService.insert(category("crm", "customer_status", DictionaryCategoryKind.DICTIONARY, TreeAbility.ROOT_ID));
        String activeId = itemService.insert(item("crm", "customer_status", "active", TreeAbility.ROOT_ID));
        String frozenId = itemService.insert(item("crm", "customer_status", "frozen", TreeAbility.ROOT_ID));

        itemService.reorder(List.of(frozenId, activeId));

        assertThat(itemService.rootItems("crm", "customer_status"))
                .extracting(DictionaryItem::getCode)
                .containsExactly("frozen", "active");
    }

    private DictionaryCategory category(String applicationAlias,
                                        String alias,
                                        DictionaryCategoryKind kind,
                                        String parentId) {
        DictionaryCategory category = new DictionaryCategory();
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        category.setCategoryKind(kind);
        category.setParentId(parentId);
        category.setTitle(alias);
        return category;
    }

    private DictionaryItem item(String applicationAlias, String categoryAlias, String code, String parentId) {
        return titledItem(applicationAlias, categoryAlias, code, code, parentId);
    }

    private DictionaryItem titledItem(String applicationAlias,
                                      String categoryAlias,
                                      String code,
                                      String title,
                                      String parentId) {
        DictionaryCategory category = categoryService.requireDictionaryCategory(applicationAlias, categoryAlias);
        DictionaryItem item = new DictionaryItem();
        item.setCategoryId(category.getId());
        item.setCategoryAlias(category.getAlias());
        item.setCode(code);
        item.setParentId(parentId);
        item.setTitle(title);
        return item;
    }
}
