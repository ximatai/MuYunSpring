package net.ximatai.muyun.spring.platform.web;

import jakarta.annotation.PreDestroy;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** System-only, read-only delivery of the JVM instance's configured rolling log files. */
@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = RuntimeLogWebController.MODULE_ALIAS, title = "程序日志", route = "/platform/logs/runtime")
@PlatformMenu(parent = PlatformMenuGroups.LOG_MANAGEMENT, title = "程序日志", order = 40)
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@StaticModuleTenantScopePolicy(requireActiveTenant = false)
@RequestMapping("/platform.runtime_log")
public class RuntimeLogWebController {
    public static final String MODULE_ALIAS = "platform.runtime_log";
    private static final int MAX_STREAMS = 4;
    private static final int MAX_CHUNK_BYTES = 32 * 1024;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 1_000L;
    private final RuntimeLogFileService logs;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore streamSlots = new Semaphore(MAX_STREAMS);

    public RuntimeLogWebController(RuntimeLogFileService logs) {
        this.logs = logs;
    }

    @GetMapping("/query/schema")
    @CustomActionEndpoint(value = "listFiles", title = "查询程序日志文件", level = PlatformActionLevel.LIST,
            dataAuth = false)
    public QuerySchema querySchema() {
        return logs.querySchema();
    }

    @PostMapping("/query")
    @CustomActionEndpoint(value = "listFiles", title = "查询程序日志文件", level = PlatformActionLevel.LIST,
            dataAuth = false)
    public WebPageResponse<RuntimeLogFileService.RuntimeLogFile> query(@RequestBody(required = false) WebQueryRequest request) {
        return logs.query(request);
    }

    @GetMapping(value = "/files/{name}/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @CustomActionEndpoint(value = "downloadFile", title = "下载程序日志文件", level = PlatformActionLevel.RECORD,
            recordIdPathVariable = "name", dataAuth = false)
    public ResponseEntity<FileSystemResource> download(@PathVariable String name) throws IOException {
        Path file = logs.file(name);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }

    @PostMapping(value = "/active/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @CustomActionEndpoint(value = "streamActive", title = "实时查看程序日志", level = PlatformActionLevel.LIST,
            dataAuth = false)
    public SseEmitter streamActive(@RequestBody(required = false) StreamRequest request) {
        if (!streamSlots.tryAcquire()) {
            throw new PlatformException(PlatformErrorCodes.RESOURCE_IN_USE, 429,
                    "too many runtime log streams are open");
        }
        SseEmitter emitter = new SseEmitter(5 * 60_000L);
        AtomicBoolean closed = new AtomicBoolean();
        Runnable release = () -> {
            if (closed.compareAndSet(false, true)) streamSlots.release();
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(error -> release.run());
        int tailLines = request == null || request.tailLines() == null ? 200 : request.tailLines();
        streamExecutor.execute(() -> stream(emitter, tailLines, closed, release));
        return emitter;
    }

    private void stream(SseEmitter emitter, int tailLines, AtomicBoolean closed, Runnable release) {
        try {
            Path file = logs.activeFile();
            if (!sendToClient(emitter, "snapshot", Map.of("fileName", file.getFileName().toString(), "text", logs.tail(file, tailLines)))) return;
            long position = Files.size(file);
            long nextHeartbeatAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MILLIS;
            while (!closed.get()) {
                Path active = logs.activeFile();
                if (!active.equals(file)) {
                    sendToClient(emitter, "rotated", Map.of("fileName", active.getFileName().toString()));
                    emitter.complete();
                    return;
                }
                long size = Files.size(file);
                if (size < position) {
                    position = 0;
                    if (!sendToClient(emitter, "reset", Map.of("fileName", file.getFileName().toString()))) return;
                }
                if (size > position) {
                    int bytes = (int) Math.min(MAX_CHUNK_BYTES, size - position);
                    ByteBuffer content = ByteBuffer.allocate(bytes);
                    try (var channel = Files.newByteChannel(file)) {
                        channel.position(position);
                        channel.read(content);
                    }
                    position += content.position();
                    if (!sendToClient(emitter, "append", Map.of("text", new String(content.array(), 0, content.position(), StandardCharsets.UTF_8)))) return;
                }
                if (System.currentTimeMillis() >= nextHeartbeatAt) {
                    if (!sendHeartbeat(emitter)) return;
                    nextHeartbeatAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MILLIS;
                }
                Thread.sleep(250);
            }
        } catch (Exception error) {
            if (!closed.get()) {
                if (sendToClient(emitter, "error", Map.of("message", "程序日志流已中断"))) emitter.complete();
            }
        } finally {
            release.run();
        }
    }

    /** A client closing its browser tab is a normal end of a long-lived log stream. */
    private static boolean sendToClient(SseEmitter emitter, String name, Map<String, String> data) {
        try {
            send(emitter, name, data);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void send(SseEmitter emitter, String name, Map<String, String> data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    private static boolean sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("keepalive"));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @PreDestroy
    void closeStreams() {
        streamExecutor.close();
    }

    public record StreamRequest(Integer tailLines) { }
}
