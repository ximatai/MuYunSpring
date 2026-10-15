package net.ximatai.muyun.spring.platform.dictionary;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.option.OptionQuery;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DictionaryOptionSourceTest {
    @Test void globalFallbackUsesMissingCategoryCodeRatherThanItsText() {
        var items = mock(DictionaryItemService.class);
        when(items.listItems("sales", "status", true)).thenThrow(
                new PlatformException(DictionaryCategoryService.CATEGORY_NOT_FOUND, 404, "未找到字典类别"));
        var global = new DictionaryCategory(); global.setId("global");
        when(items.globalOptionCategory("sales", "status")).thenAnswer(invocation -> {
            assertThat(TenantContext.tenantFilterBypassed()).isTrue();
            return global;
        });
        when(items.listItems("global", true)).thenReturn(List.of());
        try (var scope = TenantContext.use("tenant")) {
            assertThat(new DictionaryOptionSource("sales", "status", items).options(OptionQuery.enabledOnly())).isEmpty();
            assertThat(TenantContext.currentTenantId()).contains("tenant");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
        verify(items).listItems("sales", "status", true);
        verify(items).listItems("global", true);
    }

    @Test void unrelatedFailureWithTheOldTextNeverFallsBack() {
        var items = mock(DictionaryItemService.class);
        var failure = new PlatformException("Dictionary category requires existing category: misleading text");
        when(items.listItems("sales", "status", true)).thenThrow(failure);
        try (var scope = TenantContext.use("tenant")) {
            assertThatThrownBy(() -> new DictionaryOptionSource("sales", "status", items).options(OptionQuery.enabledOnly()))
                    .isSameAs(failure);
        }
        verify(items).listItems("sales", "status", true);
    }
}
