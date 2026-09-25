package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.common.model.constraint.StaticFieldWriteRules;
import net.ximatai.muyun.spring.common.model.constraint.WriteOperation;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RecordAttachmentService extends AbstractAbilityService<RecordAttachment> implements
        SoftDeleteAbility<RecordAttachment> {
    public static final String MODULE_ALIAS = "platform.record_attachment";
    private static final PageRequest DEFAULT_PAGE = PageRequest.of(1, 500);

    private final FileReferenceBindingService fileBindings;

    public RecordAttachmentService(BaseDao<RecordAttachment, String> attachmentDao, FileReferenceBindingService fileBindings) {
        super(MODULE_ALIAS, RecordAttachment.class, attachmentDao);
        this.fileBindings = Objects.requireNonNull(fileBindings);
    }

    public List<RecordAttachment> listByRecord(String moduleAlias, String recordId) {
        return list(Criteria.of()
                        .eq("moduleAlias", requireText(moduleAlias, "moduleAlias"))
                        .eq("recordId", requireText(recordId, "recordId")),
                DEFAULT_PAGE,
                Sort.asc("sort"),
                Sort.asc("createdAt"));
    }

    @Transactional
    public RecordAttachment add(String moduleAlias, String recordId, RecordAttachmentCommand command) {
        RecordAttachment attachment = fromCommand(command);
        attachment.setModuleAlias(requireText(moduleAlias, "moduleAlias"));
        attachment.setRecordId(requireText(recordId, "recordId"));
        insert(attachment);
        return attachment;
    }

    @Transactional
    public RecordAttachment updateAttachment(String moduleAlias,
                                             String recordId,
                                             String attachmentId,
                                             RecordAttachmentCommand command) {
        RecordAttachment existing = requireAttachment(moduleAlias, recordId, attachmentId);
        String incomingFileId = command == null ? null : command.fileId();
        if (incomingFileId != null && !incomingFileId.isBlank()
                && !Objects.equals(existing.getFileId(), incomingFileId.trim())) {
            throw new PlatformException("attachment fileId cannot be changed: " + attachmentId);
        }
        applyMutableFields(existing, command);
        update(existing);
        return select(existing.getId());
    }

    @Transactional
    public List<RecordAttachment> deleteAttachment(String moduleAlias, String recordId, String attachmentId) {
        RecordAttachment existing = requireAttachment(moduleAlias, recordId, attachmentId);
        delete(existing);
        return listByRecord(moduleAlias, recordId);
    }

    @Transactional
    public List<RecordAttachment> replaceRecordAttachments(String moduleAlias,
                                                           String recordId,
                                                           Collection<RecordAttachmentCommand> commands) {
        if (commands == null) {
            return listByRecord(moduleAlias, recordId);
        }
        String normalizedModuleAlias = requireText(moduleAlias, "moduleAlias");
        String normalizedRecordId = requireText(recordId, "recordId");
        rejectDuplicateFileIds(commands);
        rejectDuplicateAttachmentIds(commands);
        List<RecordAttachment> existing = listByRecord(normalizedModuleAlias, normalizedRecordId);
        Map<String, RecordAttachment> existingById = existing.stream()
                .collect(Collectors.toMap(RecordAttachment::getId, Function.identity()));
        validateReplaceCommands(commands, existingById);
        Set<String> retainedIds = new LinkedHashSet<>();
        for (RecordAttachmentCommand command : commands) {
            String attachmentId = command == null ? null : trimToNull(command.id());
            if (attachmentId == null) {
                add(normalizedModuleAlias, normalizedRecordId, command);
                continue;
            }
            RecordAttachment current = existingById.get(attachmentId);
            if (current == null) {
                throw new PlatformException("record attachment does not belong to record: " + attachmentId);
            }
            updateAttachment(normalizedModuleAlias, normalizedRecordId, attachmentId, command);
            retainedIds.add(attachmentId);
        }
        for (RecordAttachment attachment : existing) {
            if (!retainedIds.contains(attachment.getId())) {
                delete(attachment);
            }
        }
        return listByRecord(normalizedModuleAlias, normalizedRecordId);
    }

    public RecordAttachment requireAttachment(String moduleAlias, String recordId, String attachmentId) {
        RecordAttachment attachment = select(requireText(attachmentId, "attachmentId"));
        if (attachment == null
                || !Objects.equals(attachment.getModuleAlias(), requireText(moduleAlias, "moduleAlias"))
                || !Objects.equals(attachment.getRecordId(), requireText(recordId, "recordId"))) {
            throw new PlatformException("record attachment does not exist: " + attachmentId);
        }
        return attachment;
    }

    @Override
    public void beforeInsert(RecordAttachment attachment) {
        normalizeAndValidate(attachment);
        StaticFieldWriteRules.validate(RecordAttachment.class, attachment, WriteOperation.INSERT);
        rejectDuplicate(attachment, duplicateCriteria(attachment),
                "record attachment fileId is duplicated: " + attachment.getFileId());
        fileBindings.bind(attachment.getTenantId(), MODULE_ALIAS, attachment.getId(), "fileId", attachment.getFileId(),
                net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition.unrestricted());
    }

    @Override
    public void beforeUpdate(RecordAttachment attachment) {
        normalizeAndValidate(attachment);
        StaticFieldWriteRules.validate(RecordAttachment.class, attachment, WriteOperation.UPDATE);
        RecordAttachment existing = selectActiveRaw(attachment.getId());
        if (existing != null && (!Objects.equals(existing.getFileId(), attachment.getFileId())
                || !Objects.equals(existing.getModuleAlias(), attachment.getModuleAlias())
                || !Objects.equals(existing.getRecordId(), attachment.getRecordId()))) {
            throw new PlatformException("attachment binding cannot be changed: " + attachment.getId());
        }
        rejectDuplicate(attachment, duplicateCriteria(attachment),
                "record attachment fileId is duplicated: " + attachment.getFileId());
    }

    private Criteria duplicateCriteria(RecordAttachment attachment) {
        return Criteria.of()
                .eq("moduleAlias", attachment.getModuleAlias())
                .eq("recordId", attachment.getRecordId())
                .eq("fileId", attachment.getFileId());
    }

    private RecordAttachment fromCommand(RecordAttachmentCommand command) {
        if (command == null) {
            throw new PlatformException("record attachment command must not be null");
        }
        RecordAttachment attachment = new RecordAttachment();
        applyMutableFields(attachment, command);
        attachment.setFileId(requireText(command.fileId(), "fileId"));
        return attachment;
    }

    private void applyMutableFields(RecordAttachment attachment, RecordAttachmentCommand command) {
        if (command == null) {
            return;
        }
        attachment.setDisplayName(trimToNull(command.displayName()));
        attachment.setSort(command.sort());
        attachment.setRemark(trimToNull(command.remark()));
    }

    private void normalizeAndValidate(RecordAttachment attachment) {
        if (attachment == null) {
            throw new PlatformException("record attachment must not be null");
        }
        attachment.setDisplayName(trimToNull(attachment.getDisplayName()));
        attachment.setRemark(trimToNull(attachment.getRemark()));
    }

    private void rejectDuplicateFileIds(Collection<RecordAttachmentCommand> commands) {
        Set<String> fileIds = new LinkedHashSet<>();
        for (RecordAttachmentCommand command : commands) {
            String fileId = requireText(command == null ? null : command.fileId(), "fileId");
            if (!fileIds.add(fileId)) {
                throw new PlatformException("record attachment fileId is duplicated: " + fileId);
            }
        }
    }

    private void rejectDuplicateAttachmentIds(Collection<RecordAttachmentCommand> commands) {
        Set<String> attachmentIds = new LinkedHashSet<>();
        for (RecordAttachmentCommand command : commands) {
            String attachmentId = command == null ? null : trimToNull(command.id());
            if (attachmentId != null && !attachmentIds.add(attachmentId)) {
                throw new PlatformException("record attachment id is duplicated: " + attachmentId);
            }
        }
    }

    private void validateReplaceCommands(Collection<RecordAttachmentCommand> commands,
                                         Map<String, RecordAttachment> existingById) {
        for (RecordAttachmentCommand command : commands) {
            if (command == null) {
                throw new PlatformException("record attachment command must not be null");
            }
            String attachmentId = trimToNull(command.id());
            if (attachmentId == null) {
                requireText(command.fileId(), "fileId");
                continue;
            }
            RecordAttachment current = existingById.get(attachmentId);
            if (current == null) {
                throw new PlatformException("record attachment does not belong to record: " + attachmentId);
            }
            String incomingFileId = requireText(command.fileId(), "fileId");
            if (!Objects.equals(current.getFileId(), incomingFileId)) {
                throw new PlatformException("attachment fileId cannot be changed: " + attachmentId);
            }
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("record attachment " + fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
