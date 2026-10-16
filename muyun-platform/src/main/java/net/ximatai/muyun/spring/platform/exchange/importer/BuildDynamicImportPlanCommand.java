package net.ximatai.muyun.spring.platform.exchange.importer;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;

import java.util.List;

public record BuildDynamicImportPlanCommand(
        String moduleAlias,
        String mainMatchFieldName,
        ImportDuplicateStrategy mainDuplicateStrategy,
        List<ChildSheetCommand> childSheets
) {
    public BuildDynamicImportPlanCommand {
        requireText(moduleAlias, TextReference.code(
                "platform.rejection.build-dynamic-import-plan-command.modulealias-must-not-be-blank",
                "moduleAlias must not be blank"));
        requireText(mainMatchFieldName, TextReference.code(
                "platform.rejection.build-dynamic-import-plan-command.mainmatchfieldname-must-not-be-blank",
                "mainMatchFieldName must not be blank"));
        if (mainDuplicateStrategy == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.build-dynamic-import-plan-command.mainduplicatestrategy-must-not-be-null",
                    "mainDuplicateStrategy must not be null"));
        }
        childSheets = childSheets == null ? List.of() : List.copyOf(childSheets);
    }

    public record ChildSheetCommand(
            String entityAlias,
            String matchFieldName,
            ImportDuplicateStrategy duplicateStrategy
    ) {
        public ChildSheetCommand {
            requireText(entityAlias, TextReference.code(
                    "platform.rejection.build-dynamic-import-plan-command.child-entityalias-must-not-be-blank",
                    "child entityAlias must not be blank"));
            requireText(matchFieldName, TextReference.code(
                    "platform.rejection.build-dynamic-import-plan-command.child-matchfieldname-must-not-be-blank",
                    "child matchFieldName must not be blank"));
            if (duplicateStrategy == null) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.build-dynamic-import-plan-command.child-duplicatestrategy-must-not-be-null",
                        "child duplicateStrategy must not be null: {value1}"), Map.of("value1", String.valueOf(entityAlias)));
            }
        }
    }

    private static void requireText(String value, TextReference source) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(source);
        }
    }
}
