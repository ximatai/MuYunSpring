package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedColumn;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ImportWorkbookGrouper {
    private final DynamicImportValueConverter valueConverter;

    public ImportWorkbookGrouper() {
        this(new DynamicImportValueConverter());
    }

    ImportWorkbookGrouper(DynamicImportValueConverter valueConverter) {
        this.valueConverter = valueConverter;
    }

    public GroupedWorkbook group(DynamicImportPlan plan, ParsedWorkbook workbook) {
        if (plan == null) {
            throw new PlatformException("dynamic import plan must not be null");
        }
        if (workbook == null) {
            throw new PlatformException("parsed workbook must not be null");
        }
        Map<String, ParsedSheet> parsedSheetsByEntity = parsedSheetsByEntity(workbook);
        DynamicImportPlan.SheetPlan mainSheetPlan = plan.mainSheet();
        ParsedSheet mainSheet = requireParsedSheet(parsedSheetsByEntity, mainSheetPlan.entityAlias());
        ImportTemporalContext temporalContext = ImportTemporalContext.from(workbook);

        List<ImportErrorRow> errorRows = new ArrayList<>();
        LinkedHashMap<String, ImportGroup> groups = buildMainGroups(mainSheetPlan, mainSheet, temporalContext, errorRows);
        validateMainMatchKeys(mainSheetPlan, groups, errorRows);
        attachChildRows(plan, parsedSheetsByEntity, groups, temporalContext, errorRows);
        return new GroupedWorkbook(groups, errorRows);
    }

    private Map<String, ParsedSheet> parsedSheetsByEntity(ParsedWorkbook workbook) {
        return workbook.sheets().stream()
                .collect(Collectors.toMap(
                        ParsedSheet::entityAlias,
                        Function.identity(),
                        (left, right) -> {
                            throw new PlatformException("parsed sheet duplicated for entity: " + left.entityAlias());
                        },
                        LinkedHashMap::new
                ));
    }

    private ParsedSheet requireParsedSheet(Map<String, ParsedSheet> parsedSheetsByEntity, String entityAlias) {
        ParsedSheet sheet = parsedSheetsByEntity.get(entityAlias);
        if (sheet == null) {
            throw new PlatformException("parsed sheet not found for import plan: " + entityAlias);
        }
        return sheet;
    }

    private LinkedHashMap<String, ImportGroup> buildMainGroups(DynamicImportPlan.SheetPlan mainSheetPlan,
                                                               ParsedSheet mainSheet,
                                                               ImportTemporalContext temporalContext,
                                                               List<ImportErrorRow> errorRows) {
        LinkedHashMap<String, ImportGroup> groups = new LinkedHashMap<>();
        Set<String> invalidRelateIds = new LinkedHashSet<>();
        for (List<String> row : mainSheet.rows()) {
            ParsedImportRow parsedRow;
            try {
                parsedRow = valueConverter.convert(mainSheetPlan, mainSheet, row, temporalContext);
            } catch (PlatformException ex) {
                errorRows.add(buildParseErrorRow(mainSheetPlan, mainSheet, row, ex.getMessage(), null));
                continue;
            }
            String relateId = normalizeKey(parsedRow.valuesByFieldName().get(ExcelExchangeProtocol.RELATE_ID_FIELD));
            if (isBlank(relateId)) {
                errorRows.add(ImportErrorRow.of(parsedRow, "主表关联标识不能为空", null));
                continue;
            }
            if (invalidRelateIds.contains(relateId)) {
                errorRows.add(ImportErrorRow.of(parsedRow, "主表关联标识重复: " + relateId, relateId));
                continue;
            }
            ImportGroup existing = groups.get(relateId);
            if (existing != null) {
                errorRows.add(ImportErrorRow.of(parsedRow, "主表关联标识重复: " + relateId, relateId));
                errorRows.add(ImportErrorRow.of(existing.mainRow(), "主表关联标识重复: " + relateId, relateId));
                groups.remove(relateId);
                invalidRelateIds.add(relateId);
                continue;
            }
            groups.put(relateId, new ImportGroup(relateId, parsedRow));
        }
        return groups;
    }

    private void validateMainMatchKeys(DynamicImportPlan.SheetPlan mainSheetPlan,
                                       LinkedHashMap<String, ImportGroup> groups,
                                       List<ImportErrorRow> errorRows) {
        Map<String, ImportGroup> groupsByMatch = new LinkedHashMap<>();
        Set<String> invalidMatchValues = new LinkedHashSet<>();
        for (ImportGroup group : new ArrayList<>(groups.values())) {
            String matchValue = normalizeKey(valueForMatch(group.mainRow(), mainSheetPlan.matchFieldName()));
            if (isBlank(matchValue)) {
                errorRows.add(ImportErrorRow.of(group.mainRow(), "主表匹配字段不能为空", group.groupKey()));
                groups.remove(group.groupKey());
                continue;
            }
            if (invalidMatchValues.contains(matchValue)) {
                errorRows.add(ImportErrorRow.of(group.mainRow(), "主表 Excel 内匹配键重复: " + matchValue, group.groupKey()));
                groups.remove(group.groupKey());
                continue;
            }
            ImportGroup existing = groupsByMatch.putIfAbsent(matchValue, group);
            if (existing != null) {
                errorRows.add(ImportErrorRow.of(group.mainRow(), "主表 Excel 内匹配键重复: " + matchValue, group.groupKey()));
                errorRows.add(ImportErrorRow.of(existing.mainRow(), "主表 Excel 内匹配键重复: " + matchValue, existing.groupKey()));
                groups.remove(group.groupKey());
                groups.remove(existing.groupKey());
                groupsByMatch.remove(matchValue);
                invalidMatchValues.add(matchValue);
            }
        }
    }

    private void attachChildRows(DynamicImportPlan plan,
                                 Map<String, ParsedSheet> parsedSheetsByEntity,
                                 LinkedHashMap<String, ImportGroup> groups,
                                 ImportTemporalContext temporalContext,
                                 List<ImportErrorRow> errorRows) {
        for (DynamicImportPlan.SheetPlan sheetPlan : plan.sheets()) {
            if (sheetPlan.main()) {
                continue;
            }
            ParsedSheet parsedSheet = parsedSheetsByEntity.get(sheetPlan.entityAlias());
            if (parsedSheet == null || parsedSheet.rows().isEmpty()) {
                continue;
            }
            Map<String, Set<String>> matchValuesByGroup = new LinkedHashMap<>();
            for (List<String> row : parsedSheet.rows()) {
                ParsedImportRow parsedRow;
                try {
                    parsedRow = valueConverter.convert(sheetPlan, parsedSheet, row, temporalContext);
                } catch (PlatformException ex) {
                    String groupKey = readRowByField(parsedSheet, row).get(ExcelExchangeProtocol.RELATE_ID_FIELD);
                    ImportGroup group = groups.get(groupKey);
                    if (group != null) group.markChildSheetCovered(sheetPlan.sheetKey());
                    errorRows.add(buildParseErrorRow(sheetPlan, parsedSheet, row, ex.getMessage(), groupKey));
                    continue;
                }
                String relateId = normalizeKey(parsedRow.valuesByFieldName().get(ExcelExchangeProtocol.RELATE_ID_FIELD));
                if (isBlank(relateId)) {
                    errorRows.add(ImportErrorRow.of(parsedRow, "子表关联标识不能为空", null));
                    continue;
                }
                ImportGroup group = groups.get(relateId);
                if (group == null) {
                    errorRows.add(ImportErrorRow.of(parsedRow, "子表未找到对应主表关联标识: " + relateId, relateId));
                    continue;
                }
                if (isCoverageOnlyChildRow(sheetPlan, parsedRow)) {
                    group.markChildSheetCovered(sheetPlan.sheetKey());
                    continue;
                }
                if (!validateChildMatchKey(sheetPlan, parsedRow, relateId, matchValuesByGroup, errorRows)) {
                    group.markChildSheetCovered(sheetPlan.sheetKey());
                    continue;
                }
                group.addChild(sheetPlan.sheetKey(), parsedRow);
            }
            for (ImportGroup group : groups.values()) {
                if (!group.isChildSheetCovered(sheetPlan.sheetKey())) {
                    errorRows.add(ImportErrorRow.of(group.mainRow(),
                            "子表未覆盖全部主表记录: " + sheetPlan.sheetName(), group.groupKey()));
                }
            }
        }
    }

    private boolean validateChildMatchKey(DynamicImportPlan.SheetPlan sheetPlan,
                                          ParsedImportRow parsedRow,
                                          String relateId,
                                          Map<String, Set<String>> matchValuesByGroup,
                                          List<ImportErrorRow> errorRows) {
        String matchValue = normalizeKey(valueForMatch(parsedRow, sheetPlan.matchFieldName()));
        if (isBlank(matchValue)) {
            errorRows.add(ImportErrorRow.of(parsedRow, "子表匹配字段不能为空", relateId));
            return false;
        }
        if (sheetPlan.duplicateStrategy() != ImportDuplicateStrategy.ERROR) {
            return true;
        }
        Set<String> values = matchValuesByGroup.computeIfAbsent(relateId, key -> new LinkedHashSet<>());
        if (!values.add(matchValue)) {
            errorRows.add(ImportErrorRow.of(parsedRow, "子表 Excel 内匹配键重复: " + matchValue, relateId));
            return false;
        }
        return true;
    }

    private ImportErrorRow buildParseErrorRow(DynamicImportPlan.SheetPlan sheetPlan,
                                              ParsedSheet parsedSheet,
                                              List<String> row,
                                              String message,
                                              String groupIdentity) {
        LinkedHashMap<String, String> rawValuesByFieldName = new LinkedHashMap<>();
        LinkedHashMap<String, String> valuesByFieldName = readRowByField(parsedSheet, row);
        for (DynamicImportPlan.FieldPlan field : sheetPlan.fields()) {
            String raw = normalizeText(valuesByFieldName.get(field.fieldName()));
            rawValuesByFieldName.put(field.fieldName(), raw);
            valuesByFieldName.put(field.fieldName(), raw);
        }
        return ImportErrorRow.of(new ParsedImportRow(sheetPlan.sheetKey(), rawValuesByFieldName, valuesByFieldName),
                message, groupIdentity);
    }

    private LinkedHashMap<String, String> readRowByField(ParsedSheet parsedSheet, List<String> row) {
        LinkedHashMap<String, String> valuesByField = new LinkedHashMap<>();
        List<String> safeRow = row == null ? List.of() : row;
        for (ParsedColumn column : parsedSheet.columns()) {
            String raw = column.columnIndex() < safeRow.size() ? safeRow.get(column.columnIndex()) : null;
            valuesByField.put(column.fieldName(), normalizeText(raw));
        }
        return valuesByField;
    }

    private boolean isCoverageOnlyChildRow(DynamicImportPlan.SheetPlan plan, ParsedImportRow parsedRow) {
        for (DynamicImportPlan.FieldPlan field : plan.fields()) {
            if (field.relateId()) {
                continue;
            }
            if (!isBlank(parsedRow.valuesByFieldName().get(field.fieldName()))) {
                return false;
            }
        }
        return true;
    }

    private Object valueForMatch(ParsedImportRow row, String fieldName) {
        if (row.convertedValues().containsKey(fieldName)) {
            return row.convertedValues().get(fieldName);
        }
        return row.valuesByFieldName().get(fieldName);
    }

    private String normalizeKey(Object value) {
        return value == null ? null : normalizeText(String.valueOf(value));
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
