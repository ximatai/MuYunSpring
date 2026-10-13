package net.ximatai.muyun.spring.common.option;

import net.ximatai.muyun.spring.common.i18n.TextReference;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class CodeTitleEnumOptionSource<E extends Enum<E> & CodeTitleEnum> implements OptionSource {
    private final Class<E> enumType;

    private CodeTitleEnumOptionSource(Class<E> enumType) {
        this.enumType = Objects.requireNonNull(enumType, "enumType must not be null");
    }

    public static <E extends Enum<E> & CodeTitleEnum> CodeTitleEnumOptionSource<E> of(Class<E> enumType) {
        return new CodeTitleEnumOptionSource<>(enumType);
    }

    @Override
    public OptionBinding binding() {
        return OptionBinding.enumType(enumType);
    }

    @Override
    public List<OptionItem> options(OptionQuery query) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(this::toOption)
                .filter(option -> query == null || query.parentCode() == null)
                .toList();
    }

    private OptionItem toOption(E value) {
        return new OptionItem(value.getCode(), value.getTitle(), true, value.ordinal() + 1, null,
                TextReference.code(
                        "enum/" + enumType.getName() + "/" + value.getCode() + "/title", value.getTitle()));
    }
}
