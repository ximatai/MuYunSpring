package net.ximatai.muyun.spring.platform.dictionary;

import net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources;

import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.common.option.OptionQuery;
import net.ximatai.muyun.spring.common.option.OptionSource;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class DictionaryOptionSource implements OptionSource {
    private final String applicationAlias;
    private final String categoryAlias;
    private final DictionaryItemService itemService;

    public DictionaryOptionSource(String applicationAlias,
                                  String categoryAlias,
                                  DictionaryItemService itemService) {
        this.applicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        this.categoryAlias = PlatformNameRules.requireIdentifier(categoryAlias, "dictionaryCategoryAlias");
        this.itemService = Objects.requireNonNull(itemService, "itemService must not be null");
    }

    @Override
    public OptionBinding binding() {
        return OptionBinding.dictionary(applicationAlias, categoryAlias);
    }

    @Override
    public List<OptionItem> options(OptionQuery query) {
        OptionQuery effectiveQuery = query == null ? OptionQuery.enabledOnly() : query;
        List<DictionaryItem> items = readWithGlobalFallback(
                () -> itemService.listItems(applicationAlias, categoryAlias, effectiveQuery.onlyEnabled()),
                categoryId -> itemService.listItems(categoryId, effectiveQuery.onlyEnabled()));
        Map<String, DictionaryItem> itemById = items.stream()
                .collect(Collectors.toMap(DictionaryItem::getId, Function.identity()));
        return items.stream()
                .map(item -> toOption(item, itemById))
                .filter(option -> effectiveQuery.parentCode() == null
                        || effectiveQuery.parentCode().equals(option.parentCode()))
                .toList();
    }

    @Override
    public OptionItem resolve(String code) {
        return readWithGlobalFallback(
                () -> resolvedOption(itemService.resolveItem(applicationAlias, categoryAlias, code)),
                categoryId -> resolvedOption(itemService.resolveItem(categoryId, code)));
    }

    /** Uses the same tenant/global dictionary selection as displayed candidates. */
    public boolean containsEnabledCode(String code) {
        return readWithGlobalFallback(
                () -> itemService.resolveEnabledItem(applicationAlias, categoryAlias, code) != null,
                categoryId -> itemService.resolveEnabledItem(categoryId, code) != null);
    }

    private OptionItem resolvedOption(DictionaryItem item) {
        if (item == null) return null;
        DictionaryItem parent = itemService.select(item.getParentId());
        String parentCode = parent == null ? null : parent.getCode();
        return new OptionItem(item.getCode(), item.getTitle(), Boolean.TRUE.equals(item.getEnabled()),
                item.getSortOrder(), parentCode, ConfigurationTextSources.dictionaryTitle(item.getId(), item.getTitle()));
    }

    private <T> T readWithGlobalFallback(Supplier<T> reader, Function<String, T> globalReader) {
        try {
            return reader.get();
        } catch (PlatformException ex) {
            if (TenantContext.currentTenantId().isEmpty()
                    || !DictionaryCategoryService.CATEGORY_NOT_FOUND.equals(ex.code())) throw ex;
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter(
                    "read global dictionary option source: " + applicationAlias + "." + categoryAlias)) {
                return globalReader.apply(itemService.globalOptionCategory(applicationAlias, categoryAlias).getId());
            }
        }
    }

    private OptionItem toOption(DictionaryItem item, Map<String, DictionaryItem> itemById) {
        String parentCode = itemById.containsKey(item.getParentId())
                ? itemById.get(item.getParentId()).getCode()
                : null;
        return new OptionItem(item.getCode(), item.getTitle(), Boolean.TRUE.equals(item.getEnabled()),
                item.getSortOrder(), parentCode, ConfigurationTextSources.dictionaryTitle(item.getId(), item.getTitle()));
    }
}
