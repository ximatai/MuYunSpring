package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.ability.query.QueryCriteriaComposition;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Read-only, root-confined access to the current JVM's rolling text logs. */
@Service
public class RuntimeLogFileService {
    private static final int MAX_TAIL_BYTES = 512 * 1024;
    static final int MAX_STREAM_CHUNK_BYTES = 32 * 1024;
    private static final QuerySchema QUERY_SCHEMA = new QuerySchema(
            "runtime-log-files", null,
            new QuerySchema.QuickSearch(true, List.of("name"), List.of()),
            List.of(), List.of(), List.of(new QuerySchema.DefaultSort("lastModifiedAt", true)),
            QueryCriteriaComposition.FLAT_AND);
    private final String configuredDirectory;

    @Autowired
    public RuntimeLogFileService(@Value("${muyun.platform.runtime-log.directory:}") String configuredDirectory,
                                 @Value("${logging.file.name:}") String loggingFileName,
                                 @Value("${logging.file.path:}") String loggingFilePath) {
        this.configuredDirectory = configuredDirectory;
        validateOutputDirectory(configuredDirectory, loggingFileName, loggingFilePath);
    }

    RuntimeLogFileService(String configuredDirectory) {
        this.configuredDirectory = configuredDirectory;
    }

    public RuntimeLogFiles files() {
        List<Path> paths = regularLogFiles();
        Path active = activeFileOrNull(paths);
        return new RuntimeLogFiles(paths.stream().map(path -> descriptor(path, path.equals(active))).toList());
    }

    /** Adapts host-local files to the platform's source-neutral standard list query contract. */
    public QuerySchema querySchema() {
        return QUERY_SCHEMA;
    }

    public WebPageResponse<RuntimeLogFile> query(WebQueryRequest request) {
        String keyword = request == null || request.quickSearch() == null ? "" : request.quickSearch().trim().toLowerCase(java.util.Locale.ROOT);
        List<Path> paths = regularLogFiles();
        Path active = activeFileOrNull(paths);
        List<RuntimeLogFile> matched = paths.stream()
                .filter(path -> keyword.isEmpty() || path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains(keyword))
                .map(path -> descriptor(path, path.equals(active)))
                .toList();
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        int from = Math.min((page.pageNum() - 1) * page.pageSize(), matched.size());
        int to = Math.min(from + page.pageSize(), matched.size());
        long total = matched.size();
        long pages = total == 0 ? 0 : (total + page.pageSize() - 1) / page.pageSize();
        return new WebPageResponse<>(matched.subList(from, to), total, page.pageNum(), page.pageSize(), pages, true, null);
    }

    public Path activeFile() {
        Path active = activeFileOrNull(regularLogFiles());
        if (active == null) {
            throw new PlatformException(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404,
                    "no readable active runtime log file is available");
        }
        return active;
    }

    public Path file(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
            throw new PlatformException("runtime log file name is invalid");
        }
        Path candidate = directory().resolve(name).normalize();
        if (!candidate.getParent().equals(directory()) || !isReadableLogFile(candidate)) {
            throw new PlatformException(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404,
                    "runtime log file was not found: " + name);
        }
        return candidate;
    }

    /** Holds the no-follow file handle for one download so path replacement cannot change its content. */
    RuntimeLogFileDownload openDownload(String name) {
        Path file = file(name);
        OpenedLogFile opened = null;
        try {
            opened = openStableFile(file);
            return new RuntimeLogFileDownload(opened.channel().size(), opened);
        } catch (IOException ex) {
            closeQuietly(opened);
            throw new PlatformException(PlatformErrorCodes.INTERNAL_ERROR, 500,
                    "runtime log file cannot be read", ex);
        }
    }

    public String tail(Path file, int lineCount) {
        try (OpenedLogFile opened = openStableFile(file)) {
            SnapshotRead snapshot = readSnapshot(opened.channel(), lineCount);
            verifyIdentity(file, opened.identity());
            return snapshot.text();
        } catch (IOException ex) {
            throw new PlatformException(PlatformErrorCodes.INTERNAL_ERROR, 500,
                    "runtime log file cannot be read", ex);
        }
    }

    /** Opens one active file with a snapshot and cursor derived from the same read. */
    RuntimeLogFileStream openActiveStream(int lineCount) {
        Path file = activeFile();
        OpenedLogFile opened = null;
        try {
            opened = openStableFile(file);
            SnapshotRead snapshot = readSnapshot(opened.channel(), lineCount);
            verifyIdentity(file, opened.identity());
            return new RuntimeLogFileStream(this, file, opened.identity(), opened.channel(),
                    snapshot.cursor(), snapshot.text(), snapshot.decoder());
        } catch (IOException ex) {
            closeQuietly(opened);
            throw new PlatformException(PlatformErrorCodes.INTERNAL_ERROR, 500,
                    "runtime log file cannot be read", ex);
        }
    }

    private OpenedLogFile openStableFile(Path file) throws IOException {
        FileIdentity beforeOpen = fileIdentity(file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            verifyIdentity(file, beforeOpen);
            return new OpenedLogFile(beforeOpen, channel);
        } catch (IOException ex) {
            channel.close();
            throw ex;
        }
    }

    private SnapshotRead readSnapshot(FileChannel channel, int lineCount) throws IOException {
        long size = channel.size();
        long start = Math.max(0, size - MAX_TAIL_BYTES);
        channel.position(start);
        byte[] bytes = read(channel, (int) (size - start));
        Utf8StreamDecoder decoder = new Utf8StreamDecoder();
        int contentStart = start == 0 ? 0 : firstUtf8Character(bytes);
        String text = lastLines(decoder.decode(Arrays.copyOfRange(bytes, contentStart, bytes.length)), lineCount);
        return new SnapshotRead(text, channel.position(), decoder);
    }

    private static byte[] read(FileChannel channel, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read <= 0) {
                break;
            }
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static int firstUtf8Character(byte[] bytes) {
        int index = 0;
        while (index < bytes.length && (bytes[index] & 0b1100_0000) == 0b1000_0000) {
            index++;
        }
        return index;
    }

    private static String lastLines(String text, int lineCount) {
        int boundedLines = Math.clamp(lineCount, 1, 1_000);
        String[] lines = text.split("\\R", -1);
        boolean endsWithLineBreak = text.endsWith("\n") || text.endsWith("\r");
        int lastLineExclusive = endsWithLineBreak ? lines.length - 1 : lines.length;
        int first = Math.max(0, lastLineExclusive - boundedLines);
        String tail = String.join("\n", Arrays.copyOfRange(lines, first, lastLineExclusive));
        return endsWithLineBreak && !tail.isEmpty() ? tail + "\n" : tail;
    }

    private List<Path> regularLogFiles() {
        try (var entries = Files.list(directory())) {
            return entries.filter(this::isReadableLogFile)
                    .sorted(Comparator.comparing(this::modifiedAt).reversed().thenComparing(Path::getFileName))
                    .toList();
        } catch (IOException ex) {
            throw new PlatformException(PlatformErrorCodes.INTERNAL_ERROR, 500,
                    "runtime log directory cannot be listed", ex);
        }
    }

    private Path directory() {
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            throw new PlatformConfigurationException("muyun.platform.runtime-log.directory is not configured");
        }
        Path directory = Path.of(configuredDirectory).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new PlatformConfigurationException("runtime log directory is not a readable directory");
        }
        return directory;
    }

    static void validateOutputDirectory(String configuredDirectory, String loggingFileName, String loggingFilePath) {
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return;
        }
        Path runtimeLogDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize();
        Path outputDirectory = logOutputDirectory(loggingFileName, loggingFilePath);
        if (outputDirectory == null) {
            throw new PlatformConfigurationException(
                    "runtime-log.directory requires logging.file.name or logging.file.path to be configured");
        }
        if (!runtimeLogDirectory.equals(outputDirectory)) {
            throw new PlatformConfigurationException(
                    "runtime-log.directory must match the Logback file output directory");
        }
    }

    private static Path logOutputDirectory(String loggingFileName, String loggingFilePath) {
        if (loggingFileName != null && !loggingFileName.isBlank()) {
            Path logFile = Path.of(loggingFileName).toAbsolutePath().normalize();
            return logFile.getParent();
        }
        if (loggingFilePath != null && !loggingFilePath.isBlank()) {
            return Path.of(loggingFilePath).toAbsolutePath().normalize();
        }
        return null;
    }

    private boolean isReadableLogFile(Path path) {
        String filename = path.getFileName().toString();
        return (filename.endsWith(".log") || filename.endsWith(".log.gz"))
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.isReadable(path);
    }

    private Path activeFileOrNull(List<Path> paths) {
        return paths.stream().filter(path -> !path.getFileName().toString().endsWith(".gz"))
                .findFirst().orElse(null);
    }

    private FileIdentity fileIdentity(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object fileKey = attributes.fileKey();
        if (fileKey == null) {
            throw new IOException("runtime log filesystem does not expose a stable file identity");
        }
        return new FileIdentity(file.toAbsolutePath().normalize(), fileKey);
    }

    private void verifyIdentity(Path file, FileIdentity expected) throws IOException {
        if (!expected.matches(fileIdentity(file))) {
            throw new IOException("runtime log file changed while it was being opened");
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The original read failure is more useful to the caller.
        }
    }

    private RuntimeLogFile descriptor(Path path, boolean active) {
        try {
            return new RuntimeLogFile(path.getFileName().toString(), Files.size(path), modifiedAt(path).toInstant(), active);
        } catch (IOException ex) {
            throw new PlatformException(PlatformErrorCodes.INTERNAL_ERROR, 500,
                    "runtime log file metadata cannot be read", ex);
        }
    }

    private FileTime modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            return FileTime.fromMillis(0);
        }
    }

    private record SnapshotRead(String text, long cursor, Utf8StreamDecoder decoder) { }
    private record OpenedLogFile(FileIdentity identity, FileChannel channel) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
    private record FileIdentity(Path path, Object fileKey) {
        boolean matches(FileIdentity other) {
            return other != null && path.equals(other.path) && fileKey.equals(other.fileKey);
        }
    }

    static final class RuntimeLogFileDownload implements AutoCloseable {
        private final long size;
        private final OpenedLogFile opened;

        private RuntimeLogFileDownload(long size, OpenedLogFile opened) {
            this.size = size;
            this.opened = opened;
        }

        void writeTo(OutputStream output) throws IOException {
            FileChannel channel = opened.channel();
            channel.position(0);
            ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
            long remaining = size;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer);
                if (read <= 0) {
                    break;
                }
                output.write(buffer.array(), 0, read);
                remaining -= read;
            }
        }

        boolean isOpen() {
            return opened.channel().isOpen();
        }

        @Override
        public void close() throws IOException {
            opened.close();
        }
    }

    /** State for one stream only; it never chooses files or emits HTTP protocol events. */
    static final class RuntimeLogFileStream implements AutoCloseable {
        private final RuntimeLogFileService files;
        private final Path file;
        private final FileIdentity identity;
        private final FileChannel channel;
        private final Utf8StreamDecoder decoder;
        private final String snapshotText;
        private long cursor;

        private RuntimeLogFileStream(RuntimeLogFileService files, Path file, FileIdentity identity, FileChannel channel,
                                     long cursor, String snapshotText, Utf8StreamDecoder decoder) {
            this.files = files;
            this.file = file;
            this.identity = identity;
            this.channel = channel;
            this.cursor = cursor;
            this.snapshotText = snapshotText;
            this.decoder = decoder;
        }

        StreamSnapshot snapshot() {
            return new StreamSnapshot(file.getFileName().toString(), snapshotText);
        }

        StreamPoll poll() throws IOException {
            try {
                Path active = files.activeFileOrNull(files.regularLogFiles());
                if (active == null) {
                    return StreamPoll.rotated(file.getFileName().toString());
                }
                FileIdentity activeIdentity = files.fileIdentity(active);
                if (!identity.matches(activeIdentity)) {
                    return StreamPoll.rotated(active.getFileName().toString());
                }
                long size = channel.size();
                boolean reset = size < cursor;
                if (reset) {
                    cursor = 0;
                    decoder.reset();
                }
                String text = "";
                if (size > cursor) {
                    channel.position(cursor);
                    byte[] bytes = read(channel, (int) Math.min(MAX_STREAM_CHUNK_BYTES, size - cursor));
                    if (!identity.matches(files.fileIdentity(file))) {
                        return StreamPoll.rotated(file.getFileName().toString());
                    }
                    cursor += bytes.length;
                    text = decoder.decode(bytes);
                }
                return new StreamPoll(false, reset, file.getFileName().toString(), text);
            } catch (NoSuchFileException ex) {
                return StreamPoll.rotated(file.getFileName().toString());
            }
        }

        boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    record StreamSnapshot(String fileName, String text) { }
    record StreamPoll(boolean rotated, boolean reset, String fileName, String text) {
        static StreamPoll rotated(String fileName) {
            return new StreamPoll(true, false, fileName, "");
        }
    }

    /** Keeps a trailing incomplete UTF-8 character until the next file append. */
    private static final class Utf8StreamDecoder {
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private byte[] pending = new byte[0];

        String decode(byte[] bytes) {
            byte[] input = new byte[pending.length + bytes.length];
            System.arraycopy(pending, 0, input, 0, pending.length);
            System.arraycopy(bytes, 0, input, pending.length, bytes.length);
            ByteBuffer source = ByteBuffer.wrap(input);
            CharBuffer target = CharBuffer.allocate(input.length);
            decoder.decode(source, target, false);
            pending = Arrays.copyOfRange(input, source.position(), input.length);
            target.flip();
            return target.toString();
        }

        void reset() {
            decoder.reset();
            pending = new byte[0];
        }
    }

    public record RuntimeLogFiles(List<RuntimeLogFile> files) { }
    public record RuntimeLogFile(String name, long sizeBytes, Instant lastModifiedAt, boolean active) { }
}
