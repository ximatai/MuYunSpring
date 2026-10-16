package net.ximatai.muyun.spring.platform.exchange.protocol;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;

public final class ExcelExchangeProtocol {
    public static final String PROTOCOL_VERSION = "1";
    public static final String META_SHEET_NAME = "_exchange_meta";
    public static final String OPTIONS_SHEET_NAME = "_exchange_options";
    public static final String META_KEY_PROTOCOL_VERSION = "protocolVersion";
    public static final String META_KEY_MODULE_ALIAS = "moduleAlias";
    public static final String META_KEY_UI_CONFIG_ID = "uiConfigId";
    public static final String META_KEY_UI_CONFIG_TITLE = "uiConfigTitle";
    public static final String META_KEY_TIME_ZONE = "timeZone";

    public static final String RELATE_ID_FIELD = "relateId";
    public static final String RELATE_ID_TECHNICAL_FIELD = "__relateId";
    public static final String RELATE_ID_TITLE = "关联标识";
    public static final net.ximatai.muyun.spring.common.i18n.TextReference RELATE_ID_TITLE_SOURCE =
            net.ximatai.muyun.spring.common.i18n.TextReference.code("exchange.column.relate-id", RELATE_ID_TITLE);

    public static final String TECHNICAL_HEADER_SEPARATOR = ".";
    public static final int TECHNICAL_HEADER_ROW_INDEX = 0;
    public static final int DISPLAY_HEADER_ROW_INDEX = 1;
    public static final int MIN_HEADER_ROW_COUNT = 2;

    private ExcelExchangeProtocol() {
    }

    public static boolean isMetaSheet(String sheetName) {
        return META_SHEET_NAME.equals(sheetName);
    }

    public static boolean isInternalSheet(String sheetName) {
        return isMetaSheet(sheetName) || OPTIONS_SHEET_NAME.equals(sheetName);
    }

    public static String composeTechnicalField(String entityAlias, String fieldName) {
        requireText(fieldName, TextReference.code(
                "platform.rejection.excel-exchange-protocol.fieldname-must-not-be-blank",
                "fieldName must not be blank"));
        if (RELATE_ID_FIELD.equals(fieldName)) {
            return RELATE_ID_TECHNICAL_FIELD;
        }
        requireText(entityAlias, TextReference.code(
                "platform.rejection.excel-exchange-protocol.entityalias-must-not-be-blank",
                "entityAlias must not be blank"));
        return entityAlias + TECHNICAL_HEADER_SEPARATOR + fieldName;
    }

    public static TechnicalField parseTechnicalField(String technicalField) {
        requireText(technicalField, TextReference.code(
                "platform.rejection.excel-exchange-protocol.technicalfield-must-not-be-blank",
                "technicalField must not be blank"));
        if (RELATE_ID_TECHNICAL_FIELD.equals(technicalField)) {
            return new TechnicalField(null, RELATE_ID_FIELD);
        }
        int index = technicalField.lastIndexOf(TECHNICAL_HEADER_SEPARATOR);
        if (index <= 0 || index >= technicalField.length() - 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-protocol.invalid-exchange-technical-field",
                    "invalid exchange technical field: {value1}"), Map.of("value1", String.valueOf(technicalField)));
        }
        String entityAlias = technicalField.substring(0, index);
        String fieldName = technicalField.substring(index + 1);
        requireText(entityAlias, TextReference.code(
                "platform.rejection.excel-exchange-protocol.technical-field-entityalias-must-not-be-blank",
                "technical field entityAlias must not be blank"));
        requireText(fieldName, TextReference.code(
                "platform.rejection.excel-exchange-protocol.technical-field-fieldname-must-not-be-blank",
                "technical field fieldName must not be blank"));
        return new TechnicalField(entityAlias, fieldName);
    }

    private static void requireText(String value, TextReference source) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(source);
        }
    }

    public record TechnicalField(String entityAlias, String fieldName) {
    }
}
