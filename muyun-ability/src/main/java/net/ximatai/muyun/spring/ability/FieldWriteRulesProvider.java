package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.Map;

/** Adapter for metadata-backed records; ordinary Java services use their model annotations. */
public interface FieldWriteRulesProvider<T extends EntityContract> {
    Map<String, FieldWriteRules> fieldWriteRules();
    boolean hasFieldWriteValue(T record, String field);
    Object fieldWriteValue(T record, String field);
    void fieldWriteValue(T record, String field, Object value);

    /** Reads a business value from an unchanged storage snapshot without adding it to the patch. */
    default Object storedFieldWriteValue(T stored, String field) {
        return fieldWriteValue(stored, field);
    }
}
