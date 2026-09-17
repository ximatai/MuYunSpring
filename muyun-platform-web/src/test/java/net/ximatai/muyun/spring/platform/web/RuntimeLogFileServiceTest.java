package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void rejectsAFileReplacementBetweenDownloadValidationAndStreamOpening() throws Exception {
        Path active = Files.writeString(directory.resolve("application.log"), "trusted log");
        Path outside = Files.createTempFile("runtime-log-secret", ".txt");
        Files.writeString(outside, "must not download");
        ReplacingDownloadLogFileService logs = new ReplacingDownloadLogFileService(directory.toString(), active, outside);
        RuntimeLogWebController controller = new RuntimeLogWebController(logs);
        try {
            StreamingResponseBody body = controller.download("application.log").getBody();

            assertThat(body).isNotNull();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThatThrownBy(() -> body.writeTo(output)).isInstanceOf(PlatformException.class);
            assertThat(output.toString()).doesNotContain("must not download");
        } finally {
            controller.closeStreams();
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void keepsTheOpenedDownloadHandleBoundToItsValidatedFileIdentity() throws Exception {
        Path active = Files.writeString(directory.resolve("application.log"), "trusted log");
        RuntimeLogFileService service = new RuntimeLogFileService(directory.toString());

        try (RuntimeLogFileService.RuntimeLogFileDownload download = service.openDownload("application.log")) {
            Files.move(active, directory.resolve("application.1.log"), StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(active, "replacement log");
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            download.writeTo(output);

            assertThat(output.toString()).isEqualTo("trusted log");
        }
    }

    @Test
    void closesTheDownloadHandleWhenResponseWritingFails() throws Exception {
        Files.writeString(directory.resolve("application.log"), "trusted log");
        CapturingDownloadLogFileService logs = new CapturingDownloadLogFileService(directory.toString());
        RuntimeLogWebController controller = new RuntimeLogWebController(logs);
        try {
            StreamingResponseBody body = controller.download("application.log").getBody();

            assertThat(body).isNotNull();
            assertThatThrownBy(() -> body.writeTo(new FailingOutputStream())).isInstanceOf(IOException.class);
            assertThat(logs.openedDownload).isNotNull();
            assertThat(logs.openedDownload.isOpen()).isFalse();
        } finally {
            controller.closeStreams();
        }
    }

    @Test
    void keepsBytesAppendedAfterTheSnapshotCursor() throws Exception {
        Path active = Files.writeString(directory.resolve("application.log"), "before\n");
        RuntimeLogFileService service = new RuntimeLogFileService(directory.toString());

        try (RuntimeLogFileService.RuntimeLogFileStream stream = service.openActiveStream(100)) {
            Files.writeString(active, "after\n", StandardOpenOption.APPEND);

            assertThat(stream.snapshot().text()).isEqualTo("before\n");
            RuntimeLogFileService.StreamPoll update = stream.poll();
            assertThat(update).extracting(RuntimeLogFileService.StreamPoll::rotated,
                            RuntimeLogFileService.StreamPoll::reset, RuntimeLogFileService.StreamPoll::text)
                    .containsExactly(false, false, "after\n");
        }
    }

    @Test
    void carriesIncompleteUtf8AcrossBothChunkAndAppendBoundaries() throws Exception {
        Path active = Files.writeString(directory.resolve("application.log"), "");
        try (RuntimeLogFileService.RuntimeLogFileStream stream = new RuntimeLogFileService(directory.toString())
                .openActiveStream(100)) {
            byte[] chinese = "你".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] firstChunk = new byte[RuntimeLogFileService.MAX_STREAM_CHUNK_BYTES];
            Arrays.fill(firstChunk, 0, firstChunk.length - 1, (byte) 'a');
            firstChunk[firstChunk.length - 1] = chinese[0];
            Files.write(active, firstChunk, StandardOpenOption.APPEND);

            RuntimeLogFileService.StreamPoll first = stream.poll();
            assertThat(first.text()).hasSize(RuntimeLogFileService.MAX_STREAM_CHUNK_BYTES - 1);
            assertThat(first.text().chars().allMatch(value -> value == 'a')).isTrue();

            Files.write(active, Arrays.copyOfRange(chinese, 1, chinese.length), StandardOpenOption.APPEND);

            assertThat(stream.poll().text()).isEqualTo("你");
        }
    }

    @Test
    void carriesAnIncompleteSnapshotCharacterIntoTheFirstAppend() throws Exception {
        byte[] character = "😀".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path active = Files.writeString(directory.resolve("application.log"), "before");
        Files.write(active, Arrays.copyOfRange(character, 0, 2), StandardOpenOption.APPEND);
        try (var stream = new RuntimeLogFileService(directory.toString()).openActiveStream(100)) {
            assertThat(stream.snapshot().text()).isEqualTo("before");
            Files.write(active, Arrays.copyOfRange(character, 2, character.length), StandardOpenOption.APPEND);
            assertThat(stream.poll().text()).isEqualTo("😀");
        }
    }

    @Test
    void startsABoundedSnapshotAtACompleteUtf8Character() throws Exception {
        Files.writeString(directory.resolve("application.log"), "你".repeat(200_000));
        try (var stream = new RuntimeLogFileService(directory.toString()).openActiveStream(1)) {
            assertThat(stream.snapshot().text()).isNotEmpty();
            assertThat(stream.snapshot().text().chars().allMatch(value -> value == '你')).isTrue();
            assertThat(stream.poll().text()).isEmpty();
        }
    }

    @Test
    void detectsSamePathReplacementBeforeReadingFromTheStaleCursor() throws Exception {
        Path active = Files.writeString(directory.resolve("application.log"), "old");
        try (RuntimeLogFileService.RuntimeLogFileStream stream = new RuntimeLogFileService(directory.toString())
                .openActiveStream(100)) {
            Files.move(active, directory.resolve("application.1.log.gz"), StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(active, "replacement-is-larger-than-old");

            RuntimeLogFileService.StreamPoll update = stream.poll();

            assertThat(update).extracting(RuntimeLogFileService.StreamPoll::rotated,
                            RuntimeLogFileService.StreamPoll::reset, RuntimeLogFileService.StreamPoll::fileName)
                    .containsExactly(true, false, "application.log");
        }
    }

    @Test
    void resetsTheCursorWhenTheSameActiveFileIsTruncated() throws Exception {
        Path active = Files.writeString(directory.resolve("application.log"), "before-truncate");
        try (RuntimeLogFileService.RuntimeLogFileStream stream = new RuntimeLogFileService(directory.toString())
                .openActiveStream(100)) {
            Files.writeString(active, "after");

            RuntimeLogFileService.StreamPoll update = stream.poll();

            assertThat(update).extracting(RuntimeLogFileService.StreamPoll::rotated,
                            RuntimeLogFileService.StreamPoll::reset, RuntimeLogFileService.StreamPoll::text)
                    .containsExactly(false, true, "after");
        }
    }

    @Test
    void reportsRotatedWhenTheActiveFileDisappearsDuringARollingGap() throws Exception {
        Path active = Files.writeString(directory.resolve("application.log"), "before");
        try (RuntimeLogFileService.RuntimeLogFileStream stream = new RuntimeLogFileService(directory.toString())
                .openActiveStream(100)) {
            Files.delete(active);

            assertThat(stream.poll()).extracting(RuntimeLogFileService.StreamPoll::rotated,
                            RuntimeLogFileService.StreamPoll::fileName)
                    .containsExactly(true, "application.log");
        }
    }

    @Test
    void closesTheHeldChannelWhenTheFileStreamIsClosed() throws Exception {
        Files.writeString(directory.resolve("application.log"), "ready\n");
        RuntimeLogFileService.RuntimeLogFileStream stream = new RuntimeLogFileService(directory.toString())
                .openActiveStream(100);

        stream.close();

        assertThat(stream.isOpen()).isFalse();
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

    @Test
    void closesAndReleasesActiveStreamsBeforeShuttingDownTheExecutor() throws Exception {
        Files.writeString(directory.resolve("application.log"), "ready\n");
        StreamOpeningLogFileService logs = new StreamOpeningLogFileService(directory.toString());
        RuntimeLogWebController controller = new RuntimeLogWebController(logs);

        SseEmitter emitter = controller.streamActive(null);
        assertThat(controller.activeStreamCount()).isOne();
        assertThat(logs.opened.await(3, TimeUnit.SECONDS)).isTrue();
        controller.closeStreams();

        assertThat(emitter).isNotNull();
        assertThat(controller.activeStreamCount()).isZero();
        assertThat(controller.activeFileStreamCount()).isZero();
        assertThat(controller.availableStreamSlots()).isEqualTo(4);
        assertThat(logs.openedStream.isOpen()).isFalse();
        assertThatThrownBy(() -> controller.streamActive(null))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }

    private static final class StreamOpeningLogFileService extends RuntimeLogFileService {
        private final CountDownLatch opened = new CountDownLatch(1);
        private RuntimeLogFileStream openedStream;

        private StreamOpeningLogFileService(String configuredDirectory) {
            super(configuredDirectory);
        }

        @Override
        RuntimeLogFileStream openActiveStream(int lineCount) {
            openedStream = super.openActiveStream(lineCount);
            opened.countDown();
            try {
                // Force shutdown to race with an opened file that has not yet
                // been registered by the controller, without timing sleeps.
                new CountDownLatch(1).await();
            } catch (InterruptedException expectedOnShutdown) {
                Thread.currentThread().interrupt();
            }
            return openedStream;
        }
    }

    private static final class ReplacingDownloadLogFileService extends RuntimeLogFileService {
        private final Path active;
        private final Path outside;
        private int fileCalls;

        private ReplacingDownloadLogFileService(String configuredDirectory, Path active, Path outside) {
            super(configuredDirectory);
            this.active = active;
            this.outside = outside;
        }

        @Override
        public Path file(String name) {
            Path candidate = super.file(name);
            if (++fileCalls == 2) {
                try {
                    Files.delete(active);
                    Files.createSymbolicLink(active, outside);
                } catch (Exception ex) {
                    throw new AssertionError("failed to replace the validated log file", ex);
                }
            }
            return candidate;
        }
    }

    private static final class CapturingDownloadLogFileService extends RuntimeLogFileService {
        private RuntimeLogFileDownload openedDownload;

        private CapturingDownloadLogFileService(String configuredDirectory) {
            super(configuredDirectory);
        }

        @Override
        RuntimeLogFileDownload openDownload(String name) {
            openedDownload = super.openDownload(name);
            return openedDownload;
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("client disconnected");
        }

        @Override
        public void write(byte[] content, int offset, int length) throws IOException {
            throw new IOException("client disconnected");
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
