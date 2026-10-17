package net.ximatai.muyun.spring.dynamic.web;

import jakarta.servlet.http.HttpServletResponse;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.importer.BuildDynamicImportPlanCommand;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportErrorFileService;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportParseResult;
import net.ximatai.muyun.spring.platform.exchange.importer.ImportDuplicateStrategy;
import net.ximatai.muyun.spring.platform.web.DynamicRuntimeRead;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
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
    static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DynamicRecordService recordService;
    private final net.ximatai.muyun.spring.platform.web.RecordExchangeWebSupport exchange;
    private final DynamicImportErrorFileService errorFileService;
    private final ActiveTenantVerifier activeTenantVerifier;
    private final ObjectProvider<TextResolver> textResolvers;
    private final ObjectProvider<LanguageCatalog> languageCatalogs;

    @Autowired
    public DynamicImportWebController(
            DynamicRecordService recordService,
            net.ximatai.muyun.spring.platform.web.RecordExchangeWebSupport exchange,
            DynamicImportErrorFileService errorFileService,
            ActiveTenantVerifier activeTenantVerifier,
            ObjectProvider<TextResolver> textResolvers,
            ObjectProvider<LanguageCatalog> languageCatalogs) {
        this.recordService = java.util.Objects.requireNonNull(recordService);
        this.exchange = java.util.Objects.requireNonNull(exchange);
        this.errorFileService = java.util.Objects.requireNonNull(errorFileService);
        this.activeTenantVerifier = java.util.Objects.requireNonNull(activeTenantVerifier);
        this.textResolvers = java.util.Objects.requireNonNull(textResolvers);
        this.languageCatalogs = java.util.Objects.requireNonNull(languageCatalogs);
    }

    @PostMapping("/parse")
    @ActionEndpoint(PlatformAction.IMPORT)
    public DynamicImportParseResult parse(
            @PathVariable String moduleAlias, @RequestParam("file") MultipartFile file) {
        return tenantScope(
                moduleAlias,
                () -> {
                    DynamicModuleDescriptor descriptor = exchangeDescriptor(moduleAlias);
                    var parsed = exchange.compatibilityPreview(moduleAlias, bytes(file));
                    var main =
                            parsed.sheets().stream()
                                    .filter(
                                            net.ximatai.muyun.spring.platform.exchange
                                                            .RecordExchangeService.Sheet
                                                    ::main)
                                    .findFirst()
                                    .orElseThrow();
                    return new DynamicImportParseResult(
                            moduleAlias,
                            main.entityAlias(),
                            main.sheetName(),
                            parsed.sheets().stream()
                                    .map(
                                            sheet ->
                                                    new DynamicImportParseResult.Sheet(
                                                            sheet.sheetName(),
                                                            sheet.sheetName(),
                                                            sheet.entityAlias(),
                                                            sheet.main(),
                                                            sheet.rowCount(),
                                                            sheet.matchFields().stream()
                                                                    .map(
                                                                            field ->
                                                                                    new DynamicImportParseResult
                                                                                            .Field(
                                                                                            field,
                                                                                            sheet.fieldTitles()
                                                                                                    .getOrDefault(
                                                                                                            field,
                                                                                                            field),
                                                                                            false,
                                                                                            true))
                                                                    .toList()))
                                    .toList());
                });
    }

    @PostMapping("/execute")
    @ActionEndpoint(PlatformAction.IMPORT)
    public DynamicImportUploadResult execute(
            @PathVariable String moduleAlias,
            @RequestPart("command") DynamicImportExecuteRequest request,
            @RequestPart("file") MultipartFile file) {
        return tenantScope(
                moduleAlias,
                () -> {
                    DynamicModuleDescriptor descriptor = exchangeDescriptor(moduleAlias);
                    var selected = buildCommand(moduleAlias, request);
                    var sheets =
                            new java.util.ArrayList<
                                    net.ximatai.muyun.spring.platform.exchange.RecordExchangeService
                                            .SheetCommand>();
                    sheets.add(
                            new net.ximatai.muyun.spring.platform.exchange.RecordExchangeService
                                    .SheetCommand(
                                    descriptor.mainEntityAlias(),
                                    selected.mainMatchFieldName(),
                                    selected.mainDuplicateStrategy()));
                    selected.childSheets()
                            .forEach(
                                    child ->
                                            sheets.add(
                                                    new net.ximatai.muyun.spring.platform.exchange
                                                            .RecordExchangeService.SheetCommand(
                                                            child.entityAlias(),
                                                            child.matchFieldName(),
                                                            child.duplicateStrategy())));
                    var result =
                            exchange.compatibilityImport(
                                    moduleAlias,
                                    bytes(file),
                                    new net.ximatai.muyun.spring.platform.exchange
                                            .RecordExchangeService.Command(
                                            null, null, sheets, java.util.List.of(), null));
                    return uploadResult(moduleAlias, result);
                });
    }

    @PostMapping("/error-file/{token}")
    @ActionEndpoint(PlatformAction.IMPORT)
    public void downloadErrorFile(
            @PathVariable String moduleAlias,
            @PathVariable String token,
            HttpServletResponse response) {
        tenantScope(
                moduleAlias,
                () -> {
                    exchangeDescriptor(moduleAlias);
                    DynamicImportErrorFileService.ErrorFilePayload payload =
                            errorFileService.get(moduleAlias, currentTenantId(moduleAlias), token);
                    if (payload == null) {
                        throw new PlatformException(
                                "dynamic import error file token not found: " + token);
                    }
                    exchange.requireCompatibilityFileRead(moduleAlias, payload.content());
                    writeXlsx(response, payload.fileName(), payload.content());
                    return null;
                });
    }

    private DynamicModuleDescriptor exchangeDescriptor(String moduleAlias) {
        DynamicModuleDescriptor descriptor = recordService.describe(moduleAlias);
        DynamicEntityDescriptor mainEntity =
                descriptor.entities().stream()
                        .filter(entity -> entity.entityAlias().equals(descriptor.mainEntityAlias()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new PlatformException(
                                                "dynamic module main entity not found: "
                                                        + descriptor.mainEntityAlias()));
        if (!mainEntity.capabilities().contains(EntityCapability.EXCHANGE.name())) {
            throw new PlatformException("dynamic entity does not support capability: EXCHANGE");
        }
        return descriptor;
    }

    private BuildDynamicImportPlanCommand buildCommand(
            String moduleAlias, DynamicImportExecuteRequest request) {
        if (request == null || request.mainSheet() == null) {
            throw new PlatformException("dynamic import execute requires mainSheet");
        }
        DynamicImportExecuteRequest.MainSheet mainSheet = request.mainSheet();
        return new BuildDynamicImportPlanCommand(
                moduleAlias,
                mainSheet.matchFieldName(),
                duplicateStrategy(mainSheet.duplicateStrategy()),
                request.childSheets().stream()
                        .map(
                                child ->
                                        new BuildDynamicImportPlanCommand.ChildSheetCommand(
                                                child.entityAlias(),
                                                child.matchFieldName(),
                                                duplicateStrategy(child.duplicateStrategy())))
                        .toList());
    }

    private ImportDuplicateStrategy duplicateStrategy(ImportDuplicateStrategy strategy) {
        return strategy == null ? ImportDuplicateStrategy.ERROR : strategy;
    }

    private DynamicImportUploadResult uploadResult(
            String moduleAlias,
            net.ximatai.muyun.spring.platform.exchange.RecordExchangeService.Result result) {
        int errorCount = result.errorCount();
        int writtenCount = result.created() + result.updated();
        var summaries =
                new java.util.LinkedHashMap<
                        String,
                        net.ximatai.muyun.spring.platform.exchange.importer
                                .ImportEntityExecutionSummary>();
        for (var row : result.rows())
            summaries.compute(
                    row.entity(),
                    (entity, current) -> {
                        var summary =
                                current == null
                                        ? new net.ximatai.muyun.spring.platform.exchange.importer
                                                .ImportEntityExecutionSummary(entity, 0, 0, 0, 0)
                                        : current;
                        return switch (row.operation()) {
                            case "CREATE" -> summary.addCreated();
                            case "UPDATE", "DRAFT" -> summary.addUpdated();
                            case "ERROR" -> summary.addError();
                            default -> summary.addSkipped();
                        };
                    });
        String errorFileName = null;
        String errorFileToken = null;
        byte[] errorWorkbookBytes =
                result.errorWorkbookBase64() == null
                        ? null
                        : java.util.Base64.getDecoder().decode(result.errorWorkbookBase64());
        if (errorWorkbookBytes != null && errorWorkbookBytes.length > 0) {
            errorFileName = moduleAlias.replace('.', '_') + "-import-errors.xlsx";
            errorFileToken =
                    errorFileService.save(
                            moduleAlias,
                            currentTenantId(moduleAlias),
                            errorFileName,
                            errorWorkbookBytes);
        }
        var message =
                errorCount == 0
                        ? TextReference.code("exchange.import.completed", "导入完成")
                        : TextReference.code(
                                "exchange.import.completed-with-errors", "导入完成，请修正异常数据后重试");
        var resolver = textResolvers == null ? null : textResolvers.getIfAvailable();
        var catalog = languageCatalogs == null ? null : languageCatalogs.getIfAvailable();
        return new DynamicImportUploadResult(
                result.created(),
                result.updated(),
                result.skipped(),
                errorCount,
                writtenCount > 0 && errorCount > 0,
                resolver == null
                        ? message.originalText()
                        : resolver.resolve(message, java.util.Map.of()).text(),
                errorFileName,
                errorFileToken,
                summaries,
                resolver == null || catalog == null
                        ? null
                        : TextPresentations.of(message, java.util.Map.of(), resolver, catalog));
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
        return "attachment; filename=\""
                + fileName.replace("\"", "_")
                + "\"; filename*=UTF-8''"
                + encoded;
    }

    private static void writeXlsx(HttpServletResponse response, String fileName, byte[] bytes) {
        try {
            response.setContentType(XLSX_CONTENT_TYPE);
            response.setHeader("Content-Disposition", contentDisposition(fileName));
            response.setHeader(
                    "Access-Control-Expose-Headers", "Content-Disposition,X-Import-FileName");
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
