package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;

public record DynamicImportCommand(
        DynamicModuleDescriptor descriptor,
        byte[] excelBytes,
        BuildDynamicImportPlanCommand buildPlanCommand
) {
    public DynamicImportCommand {
        if (descriptor == null) {
            throw new PlatformException("dynamic import requires module descriptor");
        }
        if (excelBytes == null || excelBytes.length == 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-command.dynamic-import-excel-bytes-must-not-be-empty",
                    "dynamic import excel bytes must not be empty"));
        }
        if (buildPlanCommand == null) {
            throw new PlatformException("dynamic import requires build plan command");
        }
        excelBytes = excelBytes.clone();
    }

    @Override
    public byte[] excelBytes() {
        return excelBytes.clone();
    }
}
