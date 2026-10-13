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
        List<DictionaryItem> items = readWithGlobalFallback(() ->
                itemService.listItems(applicationAlias, categoryAlias, effectiveQuery.onlyEnabled()));
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
        return readWithGlobalFallback(() -> {
            DictionaryItem item = itemService.resolveItem(applicationAlias, categoryAlias, code);
            if (item == null) {
                return null;
            }
            DictionaryItem parent = itemService.select(item.getParentId());
            String parentCode = parent == null ? null : parent.getCode();
            return new OptionItem(item.getCode(), item.getTitle(), Boolean.TRUE.equals(item.getEnabled()),
                    item.getSortOrder(), parentCode, ConfigurationTextSources.dictionaryTitle(item.getId(), item.getTitle()));
        });
    }

    private <T> T readWithGlobalFallback(Supplier<T> reader) {
        if (TenantContext.currentTenantId().isEmpty()) {
            return reader.get();
        }
        try {
            return reader.get();
        } catch (PlatformException ex) {
            if (!isMissingCategory(ex)) {
                throw ex;
            }
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter(
                    "read global dictionary option source: " + applicationAlias + "." + categoryAlias)) {
                return reader.get();
            }
        }
    }

    private boolean isMissingCategory(PlatformException ex) {
        return DictionaryCategoryService.CATEGORY_NOT_FOUND.equals(ex.code());
    }

    private OptionItem toOption(DictionaryItem item, Map<String, DictionaryItem> itemById) {
        String parentCode = itemById.containsKey(item.getParentId())
                ? itemById.get(item.getParentId()).getCode()
                : null;
        return new OptionItem(item.getCode(), item.getTitle(), Boolean.TRUE.equals(item.getEnabled()),
                item.getSortOrder(), parentCode, ConfigurationTextSources.dictionaryTitle(item.getId(), item.getTitle()));
    }
}
