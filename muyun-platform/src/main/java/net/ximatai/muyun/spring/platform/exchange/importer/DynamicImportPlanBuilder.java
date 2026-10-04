package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldCompanionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedColumn;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeSheetResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DynamicImportPlanBuilder {
    private final ExcelExchangeSheetResolver sheetResolver;

    public DynamicImportPlanBuilder() {
        this(new ExcelExchangeSheetResolver());
    }

    DynamicImportPlanBuilder(ExcelExchangeSheetResolver sheetResolver) {
        this.sheetResolver = sheetResolver;
    }

    public DynamicImportPlan build(DynamicModuleDescriptor descriptor,
                                   ParsedWorkbook workbook,
                                   BuildDynamicImportPlanCommand command) {
        validateInputs(descriptor, workbook, command);
        Map<String, DynamicEntityDescriptor> entitiesByAlias = entitiesByAlias(descriptor);
        DynamicEntityDescriptor mainEntity = requireEntity(entitiesByAlias, descriptor.mainEntityAlias(),
                "dynamic import main entity not found");
        Set<String> childEntityAliases = firstLevelChildEntityAliases(descriptor);
        Map<String, BuildDynamicImportPlanCommand.ChildSheetCommand> childCommands =
                childCommandsByEntity(command, childEntityAliases);

        ExcelExchangeSheetResolver.ResolvedSheets resolvedSheets =
                sheetResolver.resolve(workbook, mainEntity.entityAlias(), childEntityAliases);
        assertNoUnknownSheets(workbook, mainEntity.entityAlias(), childEntityAliases);

        List<DynamicImportPlan.SheetPlan> sheetPlans = new ArrayList<>();
        sheetPlans.add(buildSheetPlan(resolvedSheets.mainSheet(), mainEntity, true,
                command.mainMatchFieldName(), command.mainDuplicateStrategy()));

        for (ParsedSheet childSheet : resolvedSheets.childSheetList()) {
            BuildDynamicImportPlanCommand.ChildSheetCommand childCommand = childCommands.get(childSheet.entityAlias());
            if (!childSheet.rows().isEmpty() && childCommand == null) {
                throw new PlatformException("child sheet import strategy required: " + childSheet.entityAlias());
            }
            if (childSheet.rows().isEmpty() && childCommand == null) {
                continue;
            }
            DynamicEntityDescriptor childEntity = requireEntity(entitiesByAlias, childSheet.entityAlias(),
                    "dynamic import child entity not found");
            sheetPlans.add(buildSheetPlan(childSheet, childEntity, false,
                    childCommand.matchFieldName(), childCommand.duplicateStrategy()));
        }
        for (String commandEntityAlias : childCommands.keySet()) {
            if (!resolvedSheets.childSheets().containsKey(commandEntityAlias)) {
                throw new PlatformException("child sheet command has no parsed sheet: " + commandEntityAlias);
            }
        }

        String planSource = workbook.meta() == null ? null : workbook.meta().uiConfigId();
        return new DynamicImportPlan(command.moduleAlias(), planSource, sheetPlans);
    }

    private void validateInputs(DynamicModuleDescriptor descriptor,
                                ParsedWorkbook workbook,
                                BuildDynamicImportPlanCommand command) {
        if (descriptor == null) {
            throw new PlatformException("dynamic import requires module descriptor");
        }
        if (workbook == null) {
            throw new PlatformException("dynamic import requires parsed workbook");
        }
        if (command == null) {
            throw new PlatformException("dynamic import requires build command");
        }
        if (isBlank(descriptor.moduleAlias())) {
            throw new PlatformException("dynamic import requires descriptor moduleAlias");
        }
        if (!Objects.equals(descriptor.moduleAlias(), command.moduleAlias())) {
            throw new PlatformException("dynamic import moduleAlias mismatch: "
                    + command.moduleAlias() + "/" + descriptor.moduleAlias());
        }
        if (workbook.meta() != null && !isBlank(workbook.meta().moduleAlias())
                && !Objects.equals(workbook.meta().moduleAlias(), descriptor.moduleAlias())) {
            throw new PlatformException("dynamic import workbook moduleAlias mismatch: "
                    + workbook.meta().moduleAlias() + "/" + descriptor.moduleAlias());
        }
        if (isBlank(descriptor.mainEntityAlias())) {
            throw new PlatformException("dynamic import requires mainEntityAlias");
        }
    }

    private Map<String, DynamicEntityDescriptor> entitiesByAlias(DynamicModuleDescriptor descriptor) {
        if (descriptor.entities().isEmpty()) {
            throw new PlatformException("dynamic import requires module entities");
        }
        return descriptor.entities().stream()
                .collect(Collectors.toMap(
                        DynamicEntityDescriptor::entityAlias,
                        Function.identity(),
                        (left, right) -> {
                            throw new PlatformException("dynamic import entity duplicated: " + left.entityAlias());
                        },
                        LinkedHashMap::new
                ));
    }

    private DynamicEntityDescriptor requireEntity(Map<String, DynamicEntityDescriptor> entitiesByAlias,
                                                  String entityAlias,
                                                  String message) {
        DynamicEntityDescriptor entity = entitiesByAlias.get(entityAlias);
        if (entity == null) {
            throw new PlatformException(message + ": " + entityAlias);
        }
        return entity;
    }

    private Set<String> firstLevelChildEntityAliases(DynamicModuleDescriptor descriptor) {
        Set<String> childEntityAliases = new LinkedHashSet<>();
        for (DynamicRelationDescriptor relation : descriptor.relations()) {
            if (relation == null || !Objects.equals(relation.parentEntityAlias(), descriptor.mainEntityAlias())) {
                continue;
            }
            if (!isBlank(relation.childEntityAlias())) {
                childEntityAliases.add(relation.childEntityAlias());
            }
        }
        return childEntityAliases;
    }

    private Map<String, BuildDynamicImportPlanCommand.ChildSheetCommand> childCommandsByEntity(
            BuildDynamicImportPlanCommand command,
            Set<String> childEntityAliases
    ) {
        Map<String, BuildDynamicImportPlanCommand.ChildSheetCommand> result = new LinkedHashMap<>();
        for (BuildDynamicImportPlanCommand.ChildSheetCommand childCommand : command.childSheets()) {
            if (!childEntityAliases.contains(childCommand.entityAlias())) {
                throw new PlatformException("child sheet command entity is not first-level child: "
                        + childCommand.entityAlias());
            }
            BuildDynamicImportPlanCommand.ChildSheetCommand previous =
                    result.putIfAbsent(childCommand.entityAlias(), childCommand);
            if (previous != null) {
                throw new PlatformException("child sheet command duplicated: " + childCommand.entityAlias());
            }
        }
        return result;
    }

    private void assertNoUnknownSheets(ParsedWorkbook workbook, String mainEntityAlias, Set<String> childEntityAliases) {
        for (ParsedSheet sheet : workbook.sheets()) {
            if (sheet == null) {
                continue;
            }
            if (Objects.equals(sheet.entityAlias(), mainEntityAlias)
                    || childEntityAliases.contains(sheet.entityAlias())) {
                continue;
            }
            throw new PlatformException("sheet does not belong to dynamic module: "
                    + sheet.sheetName() + "/" + sheet.entityAlias());
        }
    }

    private DynamicImportPlan.SheetPlan buildSheetPlan(ParsedSheet sheet,
                                                       DynamicEntityDescriptor entity,
                                                       boolean main,
                                                       String matchFieldName,
                                                       ImportDuplicateStrategy duplicateStrategy) {
        Map<String, DynamicFieldDescriptor> fieldsByName = fieldsByName(entity);
        Set<String> companionFieldNames = companionFieldNames(entity);
        Set<String> columnFieldNames = sheet.columns().stream()
                .map(ParsedColumn::fieldName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        validateMatchField(sheet, fieldsByName, companionFieldNames, columnFieldNames, matchFieldName);

        List<DynamicImportPlan.FieldPlan> fields = new ArrayList<>();
        for (ParsedColumn column : sheet.columns()) {
            // The platform error workbook carries diagnostics, never a writable business field.
            if (DynamicImportErrorWorkbookBuilder.ERROR_FIELD.equals(column.fieldName())
                    && !fieldsByName.containsKey(column.fieldName())) continue;
            boolean relateId = Objects.equals(ExcelExchangeProtocol.RELATE_ID_FIELD, column.fieldName());
            boolean companion = companionFieldNames.contains(column.fieldName());
            DynamicFieldDescriptor fieldDescriptor = fieldsByName.get(column.fieldName());
            if (!relateId && fieldDescriptor == null) {
                throw new PlatformException("import field does not belong to entity: "
                        + sheet.sheetName() + "/" + column.fieldName());
            }
            fields.add(new DynamicImportPlan.FieldPlan(
                    entity.entityAlias(),
                    column.fieldName(),
                    column.title(),
                    relateId ? FieldType.TEXT : fieldDescriptor.type(),
                    relateId,
                    !relateId && !companion,
                    companion,
                    relateId || companion ? null : fieldDescriptor.reference()
            ));
        }
        return new DynamicImportPlan.SheetPlan(
                sheet.sheetName(),
                entity.entityAlias(),
                sheet.sheetName(),
                main,
                matchFieldName,
                duplicateStrategy,
                fields
        );
    }

    private Map<String, DynamicFieldDescriptor> fieldsByName(DynamicEntityDescriptor entity) {
        return entity.fields().stream()
                .collect(Collectors.toMap(
                        DynamicFieldDescriptor::fieldName,
                        Function.identity(),
                        (left, right) -> {
                            throw new PlatformException("dynamic import field duplicated: "
                                    + entity.entityAlias() + "." + left.fieldName());
                        },
                        LinkedHashMap::new
                ));
    }

    private Set<String> companionFieldNames(DynamicEntityDescriptor entity) {
        Set<String> fieldNames = new LinkedHashSet<>();
        for (DynamicFieldDescriptor field : entity.fields()) {
            if (field == null || field.companions() == null) {
                continue;
            }
            for (DynamicFieldCompanionDescriptor companion : field.companions()) {
                if (companion != null && !isBlank(companion.fieldName())) {
                    fieldNames.add(companion.fieldName());
                }
            }
        }
        return fieldNames;
    }

    private void validateMatchField(ParsedSheet sheet,
                                    Map<String, DynamicFieldDescriptor> fieldsByName,
                                    Set<String> companionFieldNames,
                                    Set<String> columnFieldNames,
                                    String matchFieldName) {
        if (!columnFieldNames.contains(matchFieldName)) {
            throw new PlatformException("match field not found in import sheet: "
                    + sheet.sheetName() + "/" + matchFieldName);
        }
        if (Objects.equals(ExcelExchangeProtocol.RELATE_ID_FIELD, matchFieldName)
                || companionFieldNames.contains(matchFieldName)
                || !fieldsByName.containsKey(matchFieldName)) {
            throw new PlatformException("match field must be entity business field: "
                    + sheet.sheetName() + "/" + matchFieldName);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
