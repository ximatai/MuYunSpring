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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Read-only, root-confined access to the current JVM's rolling text logs. */
@Service
public class RuntimeLogFileService {
    private static final int MAX_TAIL_BYTES = 512 * 1024;
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

    public String tail(Path file, int lineCount) {
        int boundedLines = Math.clamp(lineCount, 1, 1_000);
        try {
            byte[] bytes;
            try (var channel = Files.newByteChannel(file)) {
                long size = channel.size();
                long start = Math.max(0, size - MAX_TAIL_BYTES);
                channel.position(start);
                var buffer = java.nio.ByteBuffer.allocate((int) (size - start));
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        break;
                    }
                }
                bytes = Arrays.copyOf(buffer.array(), buffer.position());
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = text.split("\\R", -1);
            int first = Math.max(0, lines.length - boundedLines - 1);
            return String.join("\n", Arrays.copyOfRange(lines, first, lines.length));
        } catch (IOException ex) {
            throw new PlatformException(PlatformErrorCodes.INTERNAL_ERROR, 500,
                    "runtime log file cannot be read", ex);
        }
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

    public record RuntimeLogFiles(List<RuntimeLogFile> files) { }
    public record RuntimeLogFile(String name, long sizeBytes, Instant lastModifiedAt, boolean active) { }
}
