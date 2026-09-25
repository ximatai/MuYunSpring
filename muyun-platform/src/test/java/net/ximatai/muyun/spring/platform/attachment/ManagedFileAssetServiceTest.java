package net.ximatai.muyun.spring.platform.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedFileAssetServiceTest {
    @Test
    void persistsTheMimeTypeDetectedFromSupportedImageContent() {
        Map<String, byte[]> samples = Map.of(
                "image/png", sample("sample.png"),
                "image/jpeg", sample("sample.jpg"),
                "image/gif", sample("sample.gif"),
                "image/webp", sample("lossy.webp"));
        ManagedFileAssetDao dao = mock(ManagedFileAssetDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> invocation.<ManagedFileAsset>getArgument(0).getId());
        ManagedFileAssetService service = new ManagedFileAssetService(dao);

        inTransaction(() -> samples.forEach((mimeType, content) -> {
            ManagedFileAsset asset = service.createInline("tenant-a", "logo", mimeType, content);
            assertThat(asset.getMimeType()).isEqualTo(mimeType);
            assertThat(asset.getImageWidth()).isEqualTo(16);
            assertThat(asset.getImageHeight()).isEqualTo(8);
            assertThat(asset.getContentBase64()).startsWith("data:" + mimeType + ";base64,");
        }));
    }

    @Test
    void allowsInlineImagesUpToOneMegabyte() {
        ManagedFileAssetDao dao = mock(ManagedFileAssetDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> invocation.<ManagedFileAsset>getArgument(0).getId());
        ManagedFileAssetService service = new ManagedFileAssetService(dao);
        byte[] image = java.util.Arrays.copyOf(sample("sample.jpg"), 1024 * 1024);

        inTransaction(() -> assertThat(service.createInline("tenant-a", "avatar.jpg", "image/jpeg", image)
                .getSizeBytes()).isEqualTo(1024 * 1024));
    }

    @Test
    void rejectsADeclaredMimeTypeThatDoesNotMatchImageBytes() {
        ManagedFileAssetService service = new ManagedFileAssetService(mock(ManagedFileAssetDao.class));

        inTransaction(() -> assertThatThrownBy(() -> service.createInline("tenant-a", "logo", "image/png",
                sample("sample.jpg")))
                .hasMessage("managed file media type does not match its binary content"));
    }

    @Test
    void rejectsNonImageBytesClaimingToBeAnImage() {
        ManagedFileAssetService service = new ManagedFileAssetService(mock(ManagedFileAssetDao.class));

        inTransaction(() -> assertThatThrownBy(() -> service.createInline("tenant-a", "logo", "image/png",
                "not an image".getBytes()))
                .hasMessage("managed file content must have readable PNG, JPEG, GIF, or WebP image metadata"));
    }

    @Test
    void extractsWebpCanvasDimensionsFromAllThreeEncodings() {
        for (String name : java.util.List.of("lossy.webp", "lossless.webp", "extended.webp")) {
            ImageFileFacts facts = ImageFileFacts.read(sample(name));
            assertThat(facts.mimeType()).isEqualTo("image/webp");
            assertThat(facts.width()).isEqualTo(16);
            assertThat(facts.height()).isEqualTo(8);
        }
    }

    @Test
    void rejectsTruncatedImageHeaders() {
        ManagedFileAssetService service = new ManagedFileAssetService(mock(ManagedFileAssetDao.class));
        byte[] truncated = java.util.Arrays.copyOf(sample("sample.png"), 8);
        inTransaction(() -> assertThatThrownBy(() -> service.createInline("tenant-a", "logo.png", "image/png", truncated))
                .hasMessageContaining("readable"));
    }

    private static byte[] sample(String name) {
        try (var stream = ManagedFileAssetServiceTest.class.getResourceAsStream("/image-dimensions/" + name)) {
            return java.util.Objects.requireNonNull(stream).readAllBytes();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private void inTransaction(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            action.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
