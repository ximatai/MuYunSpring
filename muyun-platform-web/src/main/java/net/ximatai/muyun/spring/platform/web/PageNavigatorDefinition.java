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
public record PageNavigatorDefinition(List<PageNavigatorLevelDefinition> levels,
                                      List<PageContextBindingDefinition> contextBindings) {
    public PageNavigatorDefinition {
        levels = levels == null ? List.of() : List.copyOf(levels);
        contextBindings = contextBindings == null ? List.of() : List.copyOf(contextBindings);
        if (levels.isEmpty()) throw new IllegalArgumentException("navigator requires at least one level");
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (PageNavigatorLevelDefinition level : levels) {
            if (!keys.add(level.key())) {
                throw new IllegalArgumentException("navigator level keys must be unique: " + level.key());
            }
        }
        for (PageContextBindingDefinition binding : contextBindings) {
            if (binding.source() == PageContextSource.NAVIGATOR && !keys.contains(binding.sourceKey())) {
                throw new IllegalArgumentException("navigator context source does not exist: " + binding.sourceKey());
            }
            if (binding.target() == PageContextTarget.NAVIGATOR_QUERY) {
                int sourceIndex = levels.stream().map(PageNavigatorLevelDefinition::key).toList().indexOf(binding.sourceKey());
                int childIndex = levels.stream().map(PageNavigatorLevelDefinition::key)
                        .toList().indexOf(binding.targetNavigatorLevelKey());
                if (childIndex < 0) {
                    throw new IllegalArgumentException("navigator child level does not exist: "
                            + binding.targetNavigatorLevelKey());
                }
                if (sourceIndex >= 0 && childIndex <= sourceIndex) {
                    throw new IllegalArgumentException("navigator child level must follow its parent: "
                            + binding.sourceKey() + " -> " + binding.targetNavigatorLevelKey());
                }
            }
        }
    }

    public static final class Builder {
        private final List<PageNavigatorLevelDefinition> levels = new ArrayList<>();
        private final List<PageContextBindingDefinition> contextBindings = new ArrayList<>();

        public Builder level(String key, Consumer<PageNavigatorLevelDefinition.Builder> customizer) {
            PageNavigatorLevelDefinition.Builder builder = new PageNavigatorLevelDefinition.Builder(key);
            if (customizer != null) customizer.accept(builder);
            levels.add(builder.build());
            return this;
        }

        public Builder level(ModuleUiNavigatorKey key, Consumer<PageNavigatorLevelDefinition.Builder> customizer) {
            return level(key == null ? null : key.value(), customizer);
        }

        public Builder bindNavigatorToList(String sourceLevelKey, String field) {
            return bindNavigatorToList(sourceLevelKey, field, NavigatorListQueryMode.REQUIRED_SCOPE);
        }

        /**
         * Binds a navigator selection to the list query and form default. The query mode affects
         * only the list: form defaults remain a convenience value when a selection exists.
         */
        public Builder bindNavigatorToList(String sourceLevelKey, String field, NavigatorListQueryMode queryMode) {
            contextBindings.add(PageContextBindingDefinition.navigatorList(sourceLevelKey, field, queryMode));
            contextBindings.add(PageContextBindingDefinition.navigator(sourceLevelKey, PageContextTarget.FORM_DEFAULT, field));
            return this;
        }

        public Builder bindNavigatorToList(ModuleUiNavigatorKey sourceLevelKey, ModuleUiField field) {
            return bindNavigatorToList(value(sourceLevelKey), name(field));
        }

        public Builder bindNavigatorToList(ModuleUiNavigatorKey sourceLevelKey, ModuleUiField field,
                                           NavigatorListQueryMode queryMode) {
            return bindNavigatorToList(value(sourceLevelKey), name(field), queryMode);
        }

        public Builder bindNavigatorToNavigator(String sourceLevelKey, String targetLevelKey, String field) {
            contextBindings.add(PageContextBindingDefinition.navigatorToNavigator(sourceLevelKey, targetLevelKey, field));
            return this;
        }

        public Builder bindNavigatorToNavigator(ModuleUiNavigatorKey sourceLevelKey,
                                                ModuleUiNavigatorKey targetLevelKey,
                                                ModuleUiField field) {
            return bindNavigatorToNavigator(value(sourceLevelKey), value(targetLevelKey), name(field));
        }

        /** Makes a navigator selection available to one form record-picker's query context. */
        public Builder bindNavigatorToPickerQuery(String sourceLevelKey, String pickerField, String queryField) {
            contextBindings.add(PageContextBindingDefinition.navigatorToPickerQuery(sourceLevelKey, pickerField, queryField));
            return this;
        }

        public Builder bindNavigatorToPickerQuery(ModuleUiNavigatorKey sourceLevelKey, ModuleUiField pickerField,
                                                  ModuleUiField queryField) {
            return bindNavigatorToPickerQuery(value(sourceLevelKey), name(pickerField), name(queryField));
        }

        /**
         * Binds a value from the authenticated current-user context. The server remains the
         * authority for list filtering and mutation constraints; the form-default entry is only
         * a UI convenience.
         */
        public Builder bindSessionToList(String sessionKey, String field) {
            contextBindings.add(PageContextBindingDefinition.session(sessionKey, PageContextTarget.LIST_QUERY, field));
            contextBindings.add(PageContextBindingDefinition.session(sessionKey, PageContextTarget.FORM_DEFAULT, field));
            contextBindings.add(PageContextBindingDefinition.session(sessionKey,
                    PageContextTarget.MUTATION_CONSTRAINT, field));
            return this;
        }

        public Builder bindSessionToList(ModuleUiBindingKey sessionKey, ModuleUiField field) {
            return bindSessionToList(sessionKey == null ? null : sessionKey.value(), name(field));
        }

        public Builder bind(PageContextBindingDefinition binding) {
            contextBindings.add(binding);
            return this;
        }

        PageNavigatorDefinition build() {
            return new PageNavigatorDefinition(levels, contextBindings);
        }

        private static String value(ModuleUiNavigatorKey key) {
            return key == null ? null : key.value();
        }

        private static String name(ModuleUiField field) {
            return field == null ? null : field.name();
        }
    }
}
