package net.ximatai.muyun.spring.platform.exchange.protocol;

import net.ximatai.muyun.spring.common.exception.PlatformException;

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
        requireText(fieldName, "fieldName must not be blank");
        if (RELATE_ID_FIELD.equals(fieldName)) {
            return RELATE_ID_TECHNICAL_FIELD;
        }
        requireText(entityAlias, "entityAlias must not be blank");
        return entityAlias + TECHNICAL_HEADER_SEPARATOR + fieldName;
    }

    public static TechnicalField parseTechnicalField(String technicalField) {
        requireText(technicalField, "technicalField must not be blank");
        if (RELATE_ID_TECHNICAL_FIELD.equals(technicalField)) {
            return new TechnicalField(null, RELATE_ID_FIELD);
        }
        int index = technicalField.lastIndexOf(TECHNICAL_HEADER_SEPARATOR);
        if (index <= 0 || index >= technicalField.length() - 1) {
            throw new PlatformException("invalid exchange technical field: " + technicalField);
        }
        String entityAlias = technicalField.substring(0, index);
        String fieldName = technicalField.substring(index + 1);
        requireText(entityAlias, "technical field entityAlias must not be blank");
        requireText(fieldName, "technical field fieldName must not be blank");
        return new TechnicalField(entityAlias, fieldName);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
    }

    public record TechnicalField(String entityAlias, String fieldName) {
    }
}
