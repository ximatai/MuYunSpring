package net.ximatai.muyun.spring.platform.exchange;

import net.ximatai.muyun.spring.platform.exchange.model.*;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;

import java.util.*;

/** Reserved transport columns are never copied into a business record. */
public final class RecordExchangeWorkbook {
    public static final String ROW = "__exchangeRow",
            INPUT = "__inputIdentity",
            CONTEXT = "__contextOnly",
            CLEAR = "__clearFields";
    public static final String ERROR = "__error",
            SOURCE = "__sourceLanguage",
            RELATE = ExcelExchangeProtocol.RELATE_ID_FIELD;
    public static final String FIELD = "__field",
            ORIGINAL = "__original",
            REVISION = "__originalRevision";
    public static final String ORIGINAL_ID = "__originalId",
            TARGET = "__targetLanguage",
            TEXT = "__translation";
    public static final String TRANSLATION_REVISION = "__translationRevision";
    public static final String DATA = "DATA", TRANSLATIONS = "TRANSLATIONS", READING = "READING";

    public static String source(String field) {
        return "__language_" + field;
    }

    public static String reading(String field) {
        return "__reading_" + field;
    }

    private RecordExchangeWorkbook() {}

    public static List<ExcelColumnPlan> columns(
            RecordExchangeDefinition.Entity entity, String purpose) {
        var result = new ArrayList<ExcelColumnPlan>();
        result.add(new ExcelColumnPlan(RELATE, "关联标识 / Relation"));
        if (TRANSLATIONS.equals(purpose)) {
            for (var pair :
                    List.of(
                            new String[] {FIELD, "字段 / Field"},
                            new String[] {ORIGINAL, "原文（只读） / Original"},
                            new String[] {ORIGINAL_ID, "原文身份 / Original identity"},
                            new String[] {REVISION, "原文版本 / Original revision"},
                            new String[] {SOURCE, "来源语言 / Source language"},
                            new String[] {TARGET, "目标语言 / Target language"},
                            new String[] {TRANSLATION_REVISION, "译文维护版本 / Translation version"},
                            new String[] {TEXT, "人工修订（未确认） / Human draft"}))
                result.add(new ExcelColumnPlan(pair[0], pair[1]));
        } else {
            for (var field : entity.fields()) {
                if (field.companion() || field.writable() || READING.equals(purpose)) {
                    result.add(
                            new ExcelColumnPlan(
                                    READING.equals(purpose) ? reading(field.name()) : field.name(),
                                    field.title(),
                                    false,
                                    type(field),
                                    List.of()));
                    if (field.content())
                        result.add(
                                new ExcelColumnPlan(
                                        source(field.name()), "原文语言 / " + field.title()));
                    if (READING.equals(purpose) && field.content()) {
                        result.add(
                                new ExcelColumnPlan(
                                        "__original_" + field.name(), "原文 / " + field.title()));
                        result.add(
                                new ExcelColumnPlan(
                                        "__state_" + field.name(), "译文来源与状态 / " + field.title()));
                    }
                }
            }
            if (DATA.equals(purpose)) {
                result.add(new ExcelColumnPlan(CONTEXT, "仅关联上下文 / Context only"));
                result.add(new ExcelColumnPlan(CLEAR, "明确清空字段（逗号分隔） / Clear fields"));
                result.add(new ExcelColumnPlan(SOURCE, "本次填写语言 / Input language"));
            }
        }
        result.add(new ExcelColumnPlan(ROW, "文件行身份 / File row identity"));
        result.add(new ExcelColumnPlan(INPUT, "填写行标识 / Input row identity"));
        result.add(new ExcelColumnPlan(ERROR, "错误原因（修正后直接重传） / Error"));
        return List.copyOf(result);
    }

    private static ExcelValueType type(RecordExchangeDefinition.Field field) {
        if (field.reference()) return ExcelValueType.TEXT;
        return switch (field.type()) {
            case INTEGER, LONG, DECIMAL -> ExcelValueType.NUMBER;
            case BOOLEAN -> ExcelValueType.BOOLEAN;
            case DATE -> ExcelValueType.DATE;
            case TIMESTAMP -> ExcelValueType.DATE_TIME;
            case ZONED_TIMESTAMP -> ExcelValueType.DATE_TIME_WITH_TIME_ZONE;
            default -> ExcelValueType.TEXT;
        };
    }

    public static Map<String, String> values(ParsedSheet sheet, List<String> row) {
        var values = new LinkedHashMap<String, String>();
        for (var column : sheet.columns())
            values.put(
                    column.fieldName(),
                    column.columnIndex() < row.size() ? row.get(column.columnIndex()) : null);
        return values;
    }

    public static ExcelSheetPlan sheet(
            RecordExchangeDefinition.Entity entity,
            List<ExcelColumnPlan> columns,
            List<Map<String, Object>> values,
            int index) {
        return new ExcelSheetPlan(
                index == 0 ? "data" : "detail_" + index,
                entity.alias(),
                entity.main(),
                columns,
                values.stream()
                        .map(
                                row ->
                                        columns.stream()
                                                .map(column -> row.get(column.fieldName()))
                                                .toList())
                        .toList());
    }

    public static ExcelWorkbookMeta meta(RecordExchangeReceipt document, String timeZone) {
        return meta(document, timeZone, document.getLanguageTag());
    }

    public static ExcelWorkbookMeta meta(
            RecordExchangeReceipt document, String timeZone, String language) {
        return new ExcelWorkbookMeta(
                "2",
                document.getModuleAlias(),
                null,
                null,
                timeZone,
                document.getId(),
                document.getPurpose(),
                language);
    }
}
