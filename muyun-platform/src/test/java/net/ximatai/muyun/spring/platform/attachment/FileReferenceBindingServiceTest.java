package net.ximatai.muyun.spring.platform.attachment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileReferenceBindingServiceTest {
    private final FileTransferClient client = mock(FileTransferClient.class);
    private final FileReferenceOwnershipDao ownership = mock(FileReferenceOwnershipDao.class);

    private FileReferenceBindingService service() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("client", client);
        return new FileReferenceBindingService(beans.getBeanProvider(FileTransferClient.class), ownership);
    }

    @Test
    void refusesBindingWithoutBusinessTransaction() {
        assertThatThrownBy(() -> bind()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
        verifyNoInteractions(ownership, client);
    }

    @Test
    void ownershipConflictPreventsRemotePromotion() {
        when(ownership.insert(any())).thenThrow(new IllegalStateException("ownership conflict"));
        inTransaction(() -> {
            assertThatThrownBy(() -> bind()).hasMessage("ownership conflict");
            verifyNoInteractions(client);
        });
    }

    @Test
    void recordsRemoteOrphanOnBusinessRollbackAtTheSharedBindingBoundary() {
        when(client.readMetadata("file-1")).thenReturn(metadata(true));
        when(client.promote("file-1")).thenReturn(metadata(false));
        Logger logger = (Logger) LoggerFactory.getLogger(FileReferenceBindingService.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            inTransaction(() -> {
                bind();
                assertThat(events.list).isEmpty();
                TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                        callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            });
            assertThat(events.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("file-1", "test.document", "record-1", "fileId"));
            var ordered = inOrder(ownership, client);
            ordered.verify(ownership).insert(any());
            ordered.verify(client).readMetadata("file-1");
            ordered.verify(client).promote("file-1");
        } finally {
            logger.detachAppender(events);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void observesUnknownPromotionOnlyAfterValidationSucceeded(boolean reachesPromotion) {
        when(client.readMetadata("file-1")).thenReturn(metadata(reachesPromotion));
        var timeout = new IllegalStateException("response timed out after remote commit");
        when(client.promote("file-1")).thenThrow(timeout);
        Logger logger = (Logger) LoggerFactory.getLogger(FileReferenceBindingService.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            inTransaction(() -> {
                var thrown = catchThrowable(this::bind);
                if (reachesPromotion) assertThat(thrown).isSameAs(timeout);
                else assertThat(thrown).hasMessageContaining("must bind a temporary file");
            });
            if (reachesPromotion) {
                assertThat(events.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("outcome is unknown", "test.document", "record-1", "fileId", "file-1"));
            } else {
                assertThat(events.list).isEmpty();
                verify(client, never()).promote(anyString());
            }
        } finally {
            logger.detachAppender(events);
        }
    }

    private void bind() {
        service().bind("tenant-1", "test.document", "record-1", "fileId", "file-1", FileReferenceDefinition.unrestricted());
    }

    private FileTransferFileMetadata metadata(boolean temporary) {
        return new FileTransferFileMetadata("file-1", "test.pdf", "pdf", "application/pdf", 1, "sha",
                "active", temporary, Instant.now(), null, null);
    }

    private void inTransaction(Runnable action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try { action.run(); }
        finally { TransactionSynchronizationManager.clear(); }
    }
}
