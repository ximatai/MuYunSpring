package net.ximatai.muyun.spring.iam.federation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session entry for externally verified identities skips the password, so only the federation package
 * may call it. Vendor plugins verify identities through {@link ExternalLoginProtocol} and never open sessions.
 */
class VerifiedIdentitySessionEntryContractTest {
    private static final String ENTRY = ".loginWithVerifiedIdentity(";

    @Test
    void onlyFederationOpensSessionsForVerifiedIdentities() throws IOException {
        Path repository = Path.of("").toAbsolutePath().getParent();
        List<Path> callers = new ArrayList<>();
        Files.walkFileTree(repository, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return switch (directory.getFileName().toString()) {
                    case "node_modules", "build", ".git", ".gradle", ".local" -> FileVisitResult.SKIP_SUBTREE;
                    default -> FileVisitResult.CONTINUE;
                };
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.toString().endsWith(".java") && file.toString().contains("/src/main/java/")
                        && callsEntry(file)) callers.add(file);
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(callers).isNotEmpty().allSatisfy(caller ->
                assertThat(caller.toString()).contains("/net/ximatai/muyun/spring/iam/federation/"));
    }

    private static boolean callsEntry(Path path) {
        try {
            return Files.readString(path).contains(ENTRY);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
