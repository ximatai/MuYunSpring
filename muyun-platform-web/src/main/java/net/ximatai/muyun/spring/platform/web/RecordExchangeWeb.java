package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletResponse;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.platform.exchange.RecordExchangeService;
import net.ximatai.muyun.spring.web.BusinessMutation;
import net.ximatai.muyun.spring.web.ScopedWeb;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Inherited by standard static and dynamic modules; EXCHANGE remains an explicit capability
 * declaration.
 */
public interface RecordExchangeWeb<T extends EntityContract, S extends CrudAbility<T>>
        extends ScopedWeb<S> {
    @SuppressWarnings("unchecked")
    private CrudWeb<T, S> recordWeb() {
        return (CrudWeb<T, S>) this;
    }

    @PostMapping("/exchange/files/template")
    @ActionEndpoint(PlatformAction.IMPORT)
    default void exchangeTemplate(
            @RequestBody(required = false) RecordExchangeService.Export options,
            HttpServletResponse response) {
        webScope(
                () -> {
                    write(
                            response,
                            RecordExchangeWebSupport.current()
                                    .template(
                                            recordWeb(),
                                            options == null
                                                    ? new RecordExchangeService.Export(
                                                            null, null, null)
                                                    : options),
                            "template");
                    return null;
                });
    }

    @PostMapping("/exchange/files/preview")
    @ActionEndpoint(PlatformAction.IMPORT)
    @CompiledRecordScope(CompiledRecordScope.Kind.READ)
    default RecordExchangeService.Preview exchangePreview(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "command", required = false)
                    RecordExchangeService.Command command) {
        return webScope(
                () ->
                        RecordExchangeWebSupport.current()
                                .preview(recordWeb(), bytes(file), command));
    }

    @PostMapping("/exchange/files/import")
    @ActionEndpoint(PlatformAction.IMPORT)
    @BusinessMutation(actionContextRequired = false)
    @CompiledRecordScope(CompiledRecordScope.Kind.SAVE)
    default RecordExchangeService.Result exchangeImport(
            @RequestPart("file") MultipartFile file,
            @RequestPart("command") RecordExchangeService.Command command) {
        return webScope(
                () ->
                        RecordExchangeWebSupport.current()
                                .execute(recordWeb(), bytes(file), command));
    }

    @PostMapping("/exchange/files/export")
    @ActionEndpoint(PlatformAction.EXPORT)
    @CompiledRecordScope(CompiledRecordScope.Kind.READ)
    default void exchangeExport(
            @RequestBody RecordExchangeWebSupport.ExportRequest request,
            HttpServletResponse response) {
        webScope(
                () -> {
                    write(
                            response,
                            RecordExchangeWebSupport.current().export(recordWeb(), request),
                            request.options().purpose().toLowerCase(java.util.Locale.ROOT));
                    return null;
                });
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > RecordExchangeService.MAX_BYTES)
            throw new IllegalArgumentException("Upload an Excel file up to 8 MiB");
        try {
            return file.getBytes();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read the uploaded file", failure);
        }
    }

    private void write(HttpServletResponse response, byte[] bytes, String purpose) {
        String name = webScopeName().replace('.', '_') + "-" + purpose + ".xlsx";
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
        response.setContentLength(bytes.length);
        try {
            response.getOutputStream().write(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot write the exchange file", failure);
        }
    }
}
