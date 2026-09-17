package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeLogFileServiceTest {
    @TempDir
    Path directory;

    @Test
    void listsOnlyReadableRegularLogsAndMarksTheNewestOneActive() throws Exception {
        Path historical = Files.writeString(directory.resolve("application.1.log"), "old");
        Path active = Files.writeString(directory.resolve("application.log"), "new");
        Path compressed = Files.writeString(directory.resolve("application.0.log.gz"), "compressed archive");
        Files.writeString(directory.resolve("notes.txt"), "not a log");
        Files.setLastModifiedTime(historical, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(active, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));
        Files.setLastModifiedTime(compressed, FileTime.from(Instant.parse("2026-01-03T00:00:00Z")));

        var files = new RuntimeLogFileService(directory.toString()).files().files();

        assertThat(files).extracting(RuntimeLogFileService.RuntimeLogFile::name)
                .containsExactly("application.0.log.gz", "application.log", "application.1.log");
        assertThat(files).filteredOn(RuntimeLogFileService.RuntimeLogFile::active)
                .extracting(RuntimeLogFileService.RuntimeLogFile::name)
                .containsExactly("application.log");
    }

    @Test
    void confinesNamedReadsToTheConfiguredDirectoryAndReturnsTheRequestedTail() throws Exception {
        Path active = Files.writeString(directory.resolve("application.log"), "one\ntwo\nthree\n");
        RuntimeLogFileService service = new RuntimeLogFileService(directory.toString());

        assertThat(service.file("application.log")).isEqualTo(active);
        assertThat(service.tail(active, 2)).contains("two", "three");
        assertThatThrownBy(() -> service.file("../application.log"))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void exposesFilesThroughTheStandardSearchableAndPageableListContract() throws Exception {
        Files.writeString(directory.resolve("application.log"), "current");
        Files.writeString(directory.resolve("application.1.log"), "previous");
        RuntimeLogFileService service = new RuntimeLogFileService(directory.toString());

        assertThat(service.querySchema().quickSearch().fields()).containsExactly("name");
        var page = service.query(new WebQueryRequest(new WebPageRequest(1, 1), null, List.of(), null,
                java.util.Map.of(), List.of(), null, null, java.util.Map.of(), null,
                "application.1", List.of("name"), null, null, null));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).extracting(RuntimeLogFileService.RuntimeLogFile::name)
                .containsExactly("application.1.log");
        assertThat(page.pageSize()).isOne();
    }

    @Test
    void rejectsAnUnsetDirectoryInsteadOfFallingBackToTheWorkingDirectory() {
        RuntimeLogFileService service = new RuntimeLogFileService(" ");

        assertThatThrownBy(service::files).hasMessageContaining("runtime-log.directory is not configured");
    }

    @Test
    void rejectsAConfiguredDirectoryThatDoesNotMatchTheLogbackFileOutputDirectory() {
        assertThatThrownBy(() -> RuntimeLogFileService.validateOutputDirectory(directory.toString(),
                directory.resolveSibling("different").resolve("application.log").toString(), ""))
                .hasMessageContaining("runtime-log.directory must match the Logback file output directory");
    }

    @Test
    void rejectsAConfiguredDirectoryWithoutAFileLogOutput() {
        assertThatThrownBy(() -> RuntimeLogFileService.validateOutputDirectory(directory.toString(), "", ""))
                .hasMessageContaining("runtime-log.directory requires logging.file.name or logging.file.path");
    }

    @Test
    void acceptsTheConfiguredDirectoryWhenItMatchesEitherSupportedLogbackOutputSetting() {
        assertThatCode(() -> RuntimeLogFileService.validateOutputDirectory(directory.toString(),
                directory.resolve("application.log").toString(), "")).doesNotThrowAnyException();
        assertThatCode(() -> RuntimeLogFileService.validateOutputDirectory(directory.toString(), "", directory.toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void registersAsASystemModuleWithReadOnlyLogActions() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RuntimeLogWebController.class,
                    () -> new RuntimeLogWebController(new RuntimeLogFileService(directory.toString())));
            context.refresh();

            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().getFirst();

            assertThat(definition.moduleAlias()).isEqualTo(RuntimeLogWebController.MODULE_ALIAS);
            assertThat(definition.entryRoute()).isEqualTo("/platform/logs/runtime");
            assertThat(definition.tenantRequired()).isFalse();
            assertThat(definition.actions()).extracting(action -> action.actionCode())
                    .containsExactlyInAnyOrder("menu", "listFiles", "downloadFile", "streamActive");
        }
    }

    @Test
    void releasesACompletedStreamSlotBeforeAcceptingAnotherLiveLogConnection() throws Exception {
        Files.writeString(directory.resolve("application.log"), "ready\n");
        RuntimeLogWebController controller = new RuntimeLogWebController(new RuntimeLogFileService(directory.toString()));
        try {
            SseEmitter completed = controller.streamActive(null);
            SseEmitter second = controller.streamActive(null);
            SseEmitter third = controller.streamActive(null);
            SseEmitter fourth = controller.streamActive(null);

            assertThatThrownBy(() -> controller.streamActive(null))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("too many runtime log streams are open");

            completed.complete();
            SseEmitter replacement = awaitAvailableStreamSlot(controller);

            replacement.complete();
            second.complete();
            third.complete();
            fourth.complete();
        } finally {
            controller.closeStreams();
        }
    }

    private static SseEmitter awaitAvailableStreamSlot(RuntimeLogWebController controller) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        PlatformException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return controller.streamActive(null);
            } catch (PlatformException failure) {
                if (!failure.getMessage().contains("too many runtime log streams are open")) {
                    throw failure;
                }
                lastFailure = failure;
                Thread.sleep(50);
            }
        }
        throw new AssertionError("a completed runtime log stream did not release its slot", lastFailure);
    }
}
