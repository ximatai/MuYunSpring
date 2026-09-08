package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;

import java.util.List;

/** Human-facing contract for a deployed action executor that may be bound by configuration. */
public record DynamicActionExecutorDefinition(
        String executorKey,
        String title,
        String description,
        List<EntityActionCategory> supportedCategories,
        List<EntityActionLevel> supportedLevels,
        boolean bindable,
        boolean formSupported
) {
    public DynamicActionExecutorDefinition {
        if (executorKey == null || executorKey.isBlank()) {
            throw new IllegalArgumentException("dynamic action executor definition key must not be blank");
        }
        executorKey = executorKey.trim();
        title = title == null || title.isBlank() ? executorKey : title.trim();
        description = description == null || description.isBlank() ? null : description.trim();
        supportedCategories = supportedCategories == null ? List.of() : List.copyOf(supportedCategories);
        supportedLevels = supportedLevels == null ? List.of() : List.copyOf(supportedLevels);
    }

    public DynamicActionExecutorDefinition(String executorKey, String title, String description,
                                           List<EntityActionCategory> supportedCategories,
                                           List<EntityActionLevel> supportedLevels, boolean bindable) {
        this(executorKey, title, description, supportedCategories, supportedLevels, bindable, false);
    }

    /** Internal executors remain executable but are deliberately not selectable by module configuration. */
    public static DynamicActionExecutorDefinition internal(String executorKey) {
        return new DynamicActionExecutorDefinition(executorKey, executorKey, null, List.of(), List.of(), false, false);
    }

    public boolean supports(EntityActionCategory category, EntityActionLevel level) {
        return (!supportedCategories.isEmpty() && supportedCategories.contains(category))
                && (!supportedLevels.isEmpty() && supportedLevels.contains(level));
    }
}
