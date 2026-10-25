package net.ximatai.muyun.spring.iam.federation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
        List<Path> callers;
        try (Stream<Path> files = Files.walk(repository)) {
            callers = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/node_modules/") && !path.toString().contains("/build/"))
                    .filter(VerifiedIdentitySessionEntryContractTest::callsEntry)
                    .toList();
        }

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
