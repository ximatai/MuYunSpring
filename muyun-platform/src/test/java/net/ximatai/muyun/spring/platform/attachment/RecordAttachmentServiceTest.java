package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordAttachmentServiceTest {
    private final FileReferenceBindingService bindings = org.mockito.Mockito.mock(FileReferenceBindingService.class);
    private final RecordAttachmentService service = new RecordAttachmentService(new TestMemoryDao<>(), bindings);

    @Test
    void standardInsertRejectsMissingFieldsBeforeAcquiringFileOwnership() {
        RecordAttachment attachment = new RecordAttachment();
        attachment.setModuleAlias("sales.contract");
        attachment.setRecordId("contract-1");
        attachment.setFileId(" ");
        assertThatThrownBy(() -> service.insert(attachment))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fileId");
        org.mockito.Mockito.verifyNoInteractions(bindings);
    }

    @Test
    void shouldAcquireOwnershipThroughSharedBindingBeforePersistingAttachment() {
        RecordAttachment attachment = service.add("sales.contract", "contract-1", command(null, "file-1", "a.pdf", 10));
        org.mockito.Mockito.verify(bindings).bind(attachment.getTenantId(), RecordAttachmentService.MODULE_ALIAS,
                attachment.getId(), "fileId", "file-1",
                net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition.unrestricted());
    }

    @Test
    void shouldRejectMovingAttachmentThroughDirectStandardUpdate() {
        RecordAttachment original = service.add("sales.contract", "contract-1", command(null, "file-1", "a.pdf", 10));
        RecordAttachment changed = new RecordAttachment();
        changed.setId(original.getId());
        changed.setModuleAlias("sales.contract");
        changed.setRecordId("contract-2");
        changed.setFileId("file-1");
        assertThatThrownBy(() -> service.update(changed)).isInstanceOf(PlatformException.class)
                .hasMessageContaining("binding cannot be changed");
    }

    @Test
    void shouldReplaceRecordAttachmentsAsFinalState() {
        RecordAttachment first = service.add("sales.contract", "contract-1",
                command(null, "file-1", "a.pdf", 10));
        service.add("sales.contract", "contract-1",
                command(null, "file-2", "b.pdf", 20));

        List<RecordAttachment> current = service.replaceRecordAttachments("sales.contract", "contract-1", List.of(
                command(first.getId(), "file-1", "a-renamed.pdf", 30),
                command(null, "file-3", "c.pdf", 40)
        ));

        assertThat(current).extracting(RecordAttachment::getFileId)
                .containsExactly("file-1", "file-3");
        assertThat(current.getFirst().getDisplayName()).isEqualTo("a-renamed.pdf");
        assertThat(service.listByRecord("sales.contract", "contract-1")).extracting(RecordAttachment::getFileId)
                .containsExactly("file-1", "file-3");
    }

    @Test
    void shouldClearRecordAttachmentsWhenFinalStateIsEmpty() {
        service.add("sales.contract", "contract-1", command(null, "file-1", "a.pdf", 10));

        assertThat(service.replaceRecordAttachments("sales.contract", "contract-1", List.of())).isEmpty();
        assertThat(service.listByRecord("sales.contract", "contract-1")).isEmpty();
    }

    @Test
    void shouldRejectDuplicateFileIdsInSameRecord() {
        assertThatThrownBy(() -> service.replaceRecordAttachments("sales.contract", "contract-1", List.of(
                command(null, "file-1", "a.pdf", 10),
                command(null, "file-1", "a-copy.pdf", 20)
        ))).isInstanceOf(PlatformException.class)
                .hasMessage("record attachment fileId is duplicated: file-1");
    }

    @Test
    void shouldRejectChangingBoundFileId() {
        RecordAttachment attachment = service.add("sales.contract", "contract-1",
                command(null, "file-1", "a.pdf", 10));

        assertThatThrownBy(() -> service.updateAttachment("sales.contract", "contract-1", attachment.getId(),
                command(attachment.getId(), "file-2", "a.pdf", 10)))
                .isInstanceOf(PlatformException.class)
                .hasMessage("attachment fileId cannot be changed: " + attachment.getId());
    }

    @Test
    void shouldRejectForeignAttachmentIdBeforeMutatingFinalState() {
        RecordAttachment own = service.add("sales.contract", "contract-1",
                command(null, "file-1", "a.pdf", 10));
        RecordAttachment foreign = service.add("sales.contract", "contract-2",
                command(null, "file-foreign", "foreign.pdf", 10));

        assertThatThrownBy(() -> service.replaceRecordAttachments("sales.contract", "contract-1", List.of(
                command(own.getId(), "file-1", "a-renamed.pdf", 20),
                command(foreign.getId(), "file-foreign", "foreign.pdf", 30)
        ))).isInstanceOf(PlatformException.class)
                .hasMessage("record attachment does not belong to record: " + foreign.getId());

        assertThat(service.listByRecord("sales.contract", "contract-1")).singleElement()
                .satisfies(attachment -> {
                    assertThat(attachment.getId()).isEqualTo(own.getId());
                    assertThat(attachment.getDisplayName()).isEqualTo("a.pdf");
                });
    }

    @Test
    void shouldRejectDuplicateAttachmentIdsInFinalState() {
        RecordAttachment attachment = service.add("sales.contract", "contract-1",
                command(null, "file-1", "a.pdf", 10));

        assertThatThrownBy(() -> service.replaceRecordAttachments("sales.contract", "contract-1", List.of(
                command(attachment.getId(), "file-1", "a.pdf", 10),
                command(attachment.getId(), "file-2", "a-copy.pdf", 20)
        ))).isInstanceOf(PlatformException.class)
                .hasMessage("record attachment id is duplicated: " + attachment.getId());
    }

    private RecordAttachmentCommand command(String id, String fileId, String displayName, Integer sort) {
        return new RecordAttachmentCommand(id, fileId, displayName, sort, null);
    }
}
