package net.ximatai.muyun.spring.platform.exchange.importer;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookMeta;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;

import java.time.ZoneId;
import java.time.ZoneOffset;

public record ImportTemporalContext(ZoneId workbookZoneId) {
    public static final ImportTemporalContext UTC = new ImportTemporalContext(ZoneOffset.UTC);

    public ImportTemporalContext {
        if (workbookZoneId == null) {
            workbookZoneId = ZoneOffset.UTC;
        }
    }

    public static ImportTemporalContext from(ParsedWorkbook workbook) {
        ExcelWorkbookMeta meta = workbook == null ? null : workbook.meta();
        if (meta == null || meta.timeZone() == null || meta.timeZone().isBlank()) {
            return UTC;
        }
        try {
            return new ImportTemporalContext(PlatformTimeService.requireIanaZoneId(meta.timeZone()));
        } catch (RuntimeException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.import-temporal-context.exchange-workbook-timezone-is-invalid",
                    "exchange workbook timeZone is invalid: {value1}"), Map.of("value1", String.valueOf(meta.timeZone())), ex);
        }
    }
}
