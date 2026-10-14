package net.ximatai.muyun.spring.platform.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import net.ximatai.muyun.spring.common.i18n.TextReference;

public record BusinessRuleFunctionParameter(String name, String description,
        @JsonIgnore TextReference descriptionSource) {
    public BusinessRuleFunctionParameter(String name, String description) {
        this(name, description, null);
    }
}
