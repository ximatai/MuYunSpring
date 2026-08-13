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
                "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
                "image/gif", "GIF89a".getBytes(),
                "image/webp", new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});
        ManagedFileAssetDao dao = mock(ManagedFileAssetDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> invocation.<ManagedFileAsset>getArgument(0).getId());
        ManagedFileAssetService service = new ManagedFileAssetService(dao);

        inTransaction(() -> samples.forEach((mimeType, content) -> {
            ManagedFileAsset asset = service.createInline("tenant-a", "logo", mimeType, content);
            assertThat(asset.getMimeType()).isEqualTo(mimeType);
            assertThat(asset.getContentBase64()).startsWith("data:" + mimeType + ";base64,");
        }));
    }

    @Test
    void allowsInlineImagesUpToOneMegabyte() {
        ManagedFileAssetDao dao = mock(ManagedFileAssetDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> invocation.<ManagedFileAsset>getArgument(0).getId());
        ManagedFileAssetService service = new ManagedFileAssetService(dao);
        byte[] image = new byte[1024 * 1024];
        image[0] = (byte) 0xff;
        image[1] = (byte) 0xd8;
        image[2] = (byte) 0xff;

        inTransaction(() -> assertThat(service.createInline("tenant-a", "avatar.jpg", "image/jpeg", image)
                .getSizeBytes()).isEqualTo(1024 * 1024));
    }

    @Test
    void rejectsADeclaredMimeTypeThatDoesNotMatchImageBytes() {
        ManagedFileAssetService service = new ManagedFileAssetService(mock(ManagedFileAssetDao.class));

        inTransaction(() -> assertThatThrownBy(() -> service.createInline("tenant-a", "logo", "image/png",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
                .hasMessage("managed file media type does not match its binary content"));
    }

    @Test
    void rejectsNonImageBytesClaimingToBeAnImage() {
        ManagedFileAssetService service = new ManagedFileAssetService(mock(ManagedFileAssetDao.class));

        inTransaction(() -> assertThatThrownBy(() -> service.createInline("tenant-a", "logo", "image/png",
                "not an image".getBytes()))
                .hasMessage("managed file content must be a PNG, JPEG, GIF, or WebP image"));
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
