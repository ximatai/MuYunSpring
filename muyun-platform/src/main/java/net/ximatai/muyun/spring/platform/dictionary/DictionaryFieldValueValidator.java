package net.ximatai.muyun.spring.platform.dictionary;

import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDictionaryBinding;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicFieldValueValidator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class DictionaryFieldValueValidator implements DynamicFieldValueValidator {
    private final DictionaryItemService itemService;

    public DictionaryFieldValueValidator(DictionaryItemService itemService) {
        this.itemService = itemService;
    }

    @Override
    public void validate(String moduleAlias,
                         EntityDefinition entity,
                         FieldDefinition field,
                         Object value) {
        validate(field, value);
    }

    /** Shared by runtime record writes and metadata initial-value publication. */
    public void validate(FieldDefinition field, Object value) {
        FieldDictionaryBinding binding = field.dictionaryBinding();
        if (binding == null || value == null) {
            return;
        }
        if (binding.selectionMode() == OptionSelectionMode.MULTIPLE) {
            validateMultiple(field, binding, value);
            return;
        }
        validateCode(binding, String.valueOf(value));
    }

    private void validateMultiple(FieldDefinition field, FieldDictionaryBinding binding, Object value) {
        List<?> values = toValues(value);
        if (field.isRequired() && values.isEmpty()) {
            throw new IllegalArgumentException("required multiple dictionary field must not be empty: " + field.code());
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String code)) {
                throw new IllegalArgumentException("multiple dictionary value requires string code");
            }
            if (!seen.add(code)) {
                throw new IllegalArgumentException("duplicate dictionary code: " + code);
            }
            validateCode(binding, code);
        }
    }

    private List<?> toValues(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(values::add);
            return values;
        }
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                values.add(Array.get(value, i));
            }
            return values;
        }
        throw new IllegalArgumentException("multiple dictionary value requires collection");
    }

    private void validateCode(FieldDictionaryBinding binding, String code) {
        if (itemService.resolveEnabledItem(binding.applicationAlias(), binding.categoryAlias(), code) == null) {
            throw new IllegalArgumentException("invalid dictionary code: " + code);
        }
    }
}
