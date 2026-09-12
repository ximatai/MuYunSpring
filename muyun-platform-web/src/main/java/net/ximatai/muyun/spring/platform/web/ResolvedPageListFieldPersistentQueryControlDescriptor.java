package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.common.util.Preconditions;

import java.util.List;

/** Source-neutral descriptor for one condition in the standard query criteria tree. */
public record ResolvedPageListFieldPersistentQueryControlDescriptor(String id,
                                                                    String title,
                                                                    String fieldName,
                                                                    QueryOperator operator,
                                                                    List<Object> defaultValues)
        implements ResolvedPageListPersistentQueryControlDescriptor {
    public ResolvedPageListFieldPersistentQueryControlDescriptor {
        id = Preconditions.requireText(id, "persistent query control id");
        title = Preconditions.requireText(title, "persistent query control title");
        fieldName = Preconditions.requireText(fieldName, "persistent query field name");
        if (operator == null) {
            throw new IllegalArgumentException("persistent query field operator must not be null");
        }
        defaultValues = defaultValues == null ? List.of() : List.copyOf(defaultValues);
    }

    @Override
    @JsonProperty("source")
    public PageListPersistentQueryControlDefinition.Source source() {
        return PageListPersistentQueryControlDefinition.Source.FIELD;
    }
}
