package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.common.util.Preconditions;

import java.util.List;

/** A persistent control that contributes one condition to the standard query criteria tree. */
public record PageListFieldPersistentQueryControlDefinition(String id,
                                                            String title,
                                                            String fieldName,
                                                            QueryOperator operator,
                                                            List<Object> defaultValues)
        implements PageListPersistentQueryControlDefinition {
    public PageListFieldPersistentQueryControlDefinition {
        id = Preconditions.requireText(id, "persistent query control id");
        title = Preconditions.requireText(title, "persistent query control title");
        fieldName = Preconditions.requireText(fieldName, "persistent query field name");
        if (operator == null) {
            throw new IllegalArgumentException("persistent query field operator must not be null");
        }
        defaultValues = defaultValues == null ? List.of() : List.copyOf(defaultValues);
    }

    @Override
    public Source source() {
        return Source.FIELD;
    }
}
