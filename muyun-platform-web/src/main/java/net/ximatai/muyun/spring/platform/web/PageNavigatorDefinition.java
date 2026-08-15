package net.ximatai.muyun.spring.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Optional page-level navigator made of ordered, independently selectable levels.
 *
 * <p>A level may immediately constrain the page list and may also provide criteria to a later
 * navigator level. The declaration deliberately contains no user or role condition: the
 * descriptor resolver decides which declared levels are visible for the current request.</p>
 */
public record PageNavigatorDefinition(List<PageNavigatorLevelDefinition> levels) {
    public PageNavigatorDefinition {
        levels = levels == null ? List.of() : List.copyOf(levels);
        if (levels.isEmpty()) throw new IllegalArgumentException("navigator requires at least one level");
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (PageNavigatorLevelDefinition level : levels) {
            if (!keys.add(level.key())) {
                throw new IllegalArgumentException("navigator level keys must be unique: " + level.key());
            }
        }
        for (int index = 0; index < levels.size(); index++) {
            PageNavigatorLevelDefinition level = levels.get(index);
            for (PageNavigatorChildBindingDefinition binding : level.childBindings()) {
                int childIndex = levels.stream().map(PageNavigatorLevelDefinition::key)
                        .toList().indexOf(binding.childLevelKey());
                if (childIndex < 0) {
                    throw new IllegalArgumentException("navigator child level does not exist: "
                            + binding.childLevelKey());
                }
                if (childIndex <= index) {
                    throw new IllegalArgumentException("navigator child level must follow its parent: "
                            + level.key() + " -> " + binding.childLevelKey());
                }
            }
        }
    }

    public static final class Builder {
        private final List<PageNavigatorLevelDefinition> levels = new ArrayList<>();

        public Builder level(String key, Consumer<PageNavigatorLevelDefinition.Builder> customizer) {
            PageNavigatorLevelDefinition.Builder builder = new PageNavigatorLevelDefinition.Builder(key);
            if (customizer != null) customizer.accept(builder);
            levels.add(builder.build());
            return this;
        }

        PageNavigatorDefinition build() {
            return new PageNavigatorDefinition(levels);
        }
    }
}
