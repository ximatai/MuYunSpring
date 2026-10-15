package net.ximatai.muyun.spring.platform.exchange.importer;

import java.util.LinkedHashMap;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResolver;
import net.ximatai.muyun.spring.common.i18n.TextResources;

/** Preserves raw cells by technical field name for lossless error-workbook reconstruction. */
public record ImportErrorRow(
        String sheetKey,
        LinkedHashMap<String, String> rawValuesByFieldName,
        String message,
        String groupIdentity,
        TextReference messageSource,
        Map<String, ?> messageArguments
) {
    public ImportErrorRow {
        rawValuesByFieldName = rawValuesByFieldName == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawValuesByFieldName);
        messageArguments = messageArguments == null ? Map.of() : Map.copyOf(messageArguments);
    }

    public ImportErrorRow(String sheetKey, LinkedHashMap<String, String> rawValuesByFieldName,
                          String message, String groupIdentity) {
        this(sheetKey, rawValuesByFieldName, message, groupIdentity, null, Map.of());
    }

    public static ImportErrorRow of(ParsedImportRow row, String message, String groupIdentity) {
        return new ImportErrorRow(row.sheetKey(), row.rawValuesByFieldName(), message, groupIdentity);
    }

    public static ImportErrorRow code(ParsedImportRow row, String key, String original,
                                      Map<String, ?> arguments, String groupIdentity) {
        return new ImportErrorRow(row.sheetKey(), row.rawValuesByFieldName(), TextResources.format(original, arguments),
                groupIdentity, TextReference.code("exchange.import." + key, original), arguments);
    }

    public String presentedMessage(TextResolver resolver) {
        return resolver == null || messageSource == null ? message : resolver.resolve(messageSource, messageArguments).text();
    }
}
