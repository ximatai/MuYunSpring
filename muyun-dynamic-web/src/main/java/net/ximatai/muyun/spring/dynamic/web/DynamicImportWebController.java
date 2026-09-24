package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.platform.web.DynamicRuntimeRead;

import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.importer.BuildDynamicImportPlanCommand;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportCommand;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportErrorFileService;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportExecutionResult;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportFacade;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportParseResult;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportResult;
import net.ximatai.muyun.spring.platform.exchange.importer.ImportDuplicateStrategy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

@DynamicRuntimeRead
@RestController
@RequestMapping("/{moduleAlias:[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+}/import")
public class DynamicImportWebController {
    static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DynamicRecordService recordService;
    private final DynamicImportFacade importFacade;
    private final DynamicImportErrorFileService errorFileService;
    private final ActiveTenantVerifier activeTenantVerifier;

    public DynamicImportWebController(DynamicRecordService recordService,
                                      DynamicImportFacade importFacade,
                                      DynamicImportErrorFileService errorFileService,
                                      ActiveTenantVerifier activeTenantVerifier) {
        this.recordService = recordService;
        this.importFacade = importFacade;
        this.errorFileService = errorFileService;
        this.activeTenantVerifier = activeTenantVerifier;
    }

    @PostMapping("/parse")
    @ActionEndpoint(PlatformAction.IMPORT)
    public DynamicImportParseResult parse(@PathVariable String moduleAlias,
                                          @RequestParam("file") MultipartFile file) {
        return tenantScope(moduleAlias, () -> {
            DynamicModuleDescriptor descriptor = exchangeDescriptor(moduleAlias);
            return importFacade.parse(descriptor, bytes(file));
        });
    }

    @PostMapping("/execute")
    @ActionEndpoint(PlatformAction.IMPORT)
    public DynamicImportUploadResult execute(@PathVariable String moduleAlias,
                                             @RequestPart("command") DynamicImportExecuteRequest request,
                                             @RequestPart("file") MultipartFile file) {
        return tenantScope(moduleAlias, () -> {
            DynamicModuleDescriptor descriptor = exchangeDescriptor(moduleAlias);
            DynamicImportResult result = importFacade.importWorkbook(new DynamicImportCommand(
                    descriptor,
                    bytes(file),
                    buildCommand(moduleAlias, request)
            ));
            return uploadResult(moduleAlias, result);
        });
    }

    @PostMapping("/error-file/{token}")
    @ActionEndpoint(PlatformAction.IMPORT)
    public void downloadErrorFile(@PathVariable String moduleAlias,
                                  @PathVariable String token,
                                  HttpServletResponse response) {
        tenantScope(moduleAlias, () -> {
            exchangeDescriptor(moduleAlias);
            DynamicImportErrorFileService.ErrorFilePayload payload =
                    errorFileService.get(moduleAlias, currentTenantId(moduleAlias), token);
            if (payload == null) {
                throw new PlatformException("dynamic import error file token not found: " + token);
            }
            writeXlsx(response, payload.fileName(), payload.content());
            return null;
        });
    }

    private DynamicModuleDescriptor exchangeDescriptor(String moduleAlias) {
        DynamicModuleDescriptor descriptor = recordService.describe(moduleAlias);
        DynamicEntityDescriptor mainEntity = descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(descriptor.mainEntityAlias()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("dynamic module main entity not found: "
                        + descriptor.mainEntityAlias()));
        if (!mainEntity.capabilities().contains(EntityCapability.EXCHANGE.name())) {
            throw new PlatformException("dynamic entity does not support capability: EXCHANGE");
        }
        return descriptor;
    }

    private BuildDynamicImportPlanCommand buildCommand(String moduleAlias, DynamicImportExecuteRequest request) {
        if (request == null || request.mainSheet() == null) {
            throw new PlatformException("dynamic import execute requires mainSheet");
        }
        DynamicImportExecuteRequest.MainSheet mainSheet = request.mainSheet();
        return new BuildDynamicImportPlanCommand(
                moduleAlias,
                mainSheet.matchFieldName(),
                duplicateStrategy(mainSheet.duplicateStrategy()),
                request.childSheets().stream()
                        .map(child -> new BuildDynamicImportPlanCommand.ChildSheetCommand(
                                child.entityAlias(),
                                child.matchFieldName(),
                                duplicateStrategy(child.duplicateStrategy())
                        ))
                        .toList()
        );
    }

    private ImportDuplicateStrategy duplicateStrategy(ImportDuplicateStrategy strategy) {
        return strategy == null ? ImportDuplicateStrategy.ERROR : strategy;
    }

    private DynamicImportUploadResult uploadResult(String moduleAlias, DynamicImportResult result) {
        DynamicImportExecutionResult execution = result.executionResult();
        int errorCount = execution.errorRows().size();
        int writtenCount = execution.created() + execution.updated();
        String errorFileName = null;
        String errorFileToken = null;
        byte[] errorWorkbookBytes = result.errorWorkbookBytes();
        if (errorWorkbookBytes != null && errorWorkbookBytes.length > 0) {
            errorFileName = moduleAlias.replace('.', '_') + "-import-errors.xlsx";
            errorFileToken = errorFileService.save(moduleAlias, currentTenantId(moduleAlias), errorFileName,
                    errorWorkbookBytes);
        }
        return new DynamicImportUploadResult(
                execution.created(),
                execution.updated(),
                execution.skipped(),
                errorCount,
                writtenCount > 0 && errorCount > 0,
                errorCount == 0 ? "import completed" : "import completed with errors",
                errorFileName,
                errorFileToken
        );
    }

    private String currentTenantId(String moduleAlias) {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException(moduleAlias + " requires tenant context"));
    }

    private byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PlatformException("dynamic import file must not be empty");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new PlatformException("dynamic import file read failed", ex);
        }
    }

    static String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fileName.replace("\"", "_") + "\"; filename*=UTF-8''" + encoded;
    }

    private static void writeXlsx(HttpServletResponse response, String fileName, byte[] bytes) {
        try {
            response.setContentType(XLSX_CONTENT_TYPE);
            response.setHeader("Content-Disposition", contentDisposition(fileName));
            response.setHeader("Access-Control-Expose-Headers", "Content-Disposition,X-Import-FileName");
            response.setHeader("X-Import-FileName", fileName);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        } catch (IOException ex) {
            throw new PlatformException("dynamic import error file write failed", ex);
        }
    }

    private <T> T tenantScope(String moduleAlias, Supplier<T> action) {
        activeTenantVerifier.verifyActiveTenant(currentTenantId(moduleAlias));
        return action.get();
    }

}
