package net.ximatai.muyun.spring.common.option;

import net.ximatai.muyun.spring.common.i18n.TextReference;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeTitleEnumOptionSourceTest {
    @Test
    void shouldExposeCodeTitleEnumAsOptions() {
        CodeTitleEnumOptionSource<OrderState> source = CodeTitleEnumOptionSource.of(OrderState.class);

        assertThat(source.binding())
                .isEqualTo(OptionBinding.enumType(OrderState.class));
        assertThat(source.options()).containsExactly(
                new OptionItem("draft", "Draft", true, 1, null, TextReference.code("enum/" + OrderState.class.getName() + "/draft/title", "Draft")),
                new OptionItem("approved", "Approved", true, 2, null, TextReference.code("enum/" + OrderState.class.getName() + "/approved/title", "Approved"))
        );
        assertThat(source.resolve("approved").title()).isEqualTo("Approved");
    }

    @Test
    void shouldResolveEnumOptionSourceByBinding() {
        OptionSourceRegistry registry = new OptionSourceRegistry(List.of(new CodeTitleEnumOptionSourceProvider()));

        OptionSource source = registry.source(OptionBinding.enumType(OrderState.class));

        assertThat(source.options()).extracting(OptionItem::code)
                .containsExactly("draft", "approved");
        assertThatThrownBy(() -> registry.source(OptionBinding.dictionary("crm", "status")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported option binding");
    }

    @Test
    void shouldRejectDuplicateOptionSourceProviders() {
        assertThatThrownBy(() -> new OptionSourceRegistry(List.of(
                new CodeTitleEnumOptionSourceProvider(),
                new CodeTitleEnumOptionSourceProvider()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate option source provider");
    }

    private enum OrderState implements CodeTitleEnum {
        DRAFT("draft", "Draft"),
        APPROVED("approved", "Approved");

        private final String code;
        private final String title;

        OrderState(String code, String title) {
            this.code = code;
            this.title = title;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getTitle() {
            return title;
        }
    }
}
