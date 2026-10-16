package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** Internal persistence, reached only from a normal save or an authorized source read. */
@Repository
class BusinessContentStore {
    private final BusinessContentRevisionDao revisions;
    BusinessContentStore(BusinessContentRevisionDao revisions) { this.revisions = Objects.requireNonNull(revisions); }

    BusinessContentRevision latest(BusinessContentSource source) {
        return history(source, PageRequest.of(1, 1)).stream().findFirst().orElse(null);
    }

    BusinessContentRevision find(BusinessContentSource source, OriginalVersion version) {
        return revisions.query(identity(source).eq("id", version.revisionId()).eq("revision", version.revision()),
                PageRequest.of(1, 1)).stream().findFirst().orElse(null);
    }

    List<BusinessContentRevision> history(BusinessContentSource source, PageRequest page) {
        return revisions.query(identity(source), page, Sort.desc("revision"));
    }

    private static Criteria identity(BusinessContentSource source) {
        return Criteria.of().eq("sourceKey", source.key()).eqNullable("tenantId", source.tenantId())
                .eq("moduleAlias", source.moduleAlias()).eq("entityKey", source.entityKey())
                .eq("recordId", source.recordId()).eq("fieldKey", source.fieldKey());
    }

    record Baseline(String text) { }

    /** The row identity cannot restart when a purged business record ID is reused. */
    record OriginalVersion(String revisionId, int revision) {
        OriginalVersion {
            if (revisionId == null || revisionId.isBlank() || revisionId.length() > 64 || revision < 1)
                throw new IllegalArgumentException("Invalid business content original version");
        }
        static OriginalVersion of(BusinessContentRevision row) {
            return new OriginalVersion(row.getId(), row.getRevision());
        }
    }

    void capture(BusinessContentSource source, String text, SourceInput input, Baseline baseline) {
        if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Business content requires a transaction");
        lockRecord(source);
        var previous = latest(source);
        checkExpected(previous, input);
        // A newly enabled field may already contain business text. Capture its unknown provenance
        // before recording a real edit, without claiming that the current actor authored the old text.
        if (baseline != null && (previous == null || !Objects.equals(previous.getOriginalText(), baseline.text())))
            previous = append(source, baseline.text(), null, null, null, null, "UNTRACKED_BASELINE",
                    previous == null ? 1 : Math.addExact(previous.getRevision(), 1));
        boolean sameText = previous != null && Objects.equals(previous.getOriginalText(), text);
        String language = input != null ? input.languageTag() : sameText ? previous.getSourceLanguage() : null;
        if (sameText && Objects.equals(previous.getSourceLanguage(), language)) return;
        String author = sameText ? originalAuthor(previous) : currentActor();
        Instant authoredAt = sameText ? originalAuthoredAt(previous) : Instant.now();
        append(source, text, language, author, sameText ? previous.getOriginalAuthorName() : currentActorName(), authoredAt, sameText ? "SOURCE_LANGUAGE_CORRECTION" : "ORIGINAL_WRITE",
                previous == null ? 1 : Math.addExact(previous.getRevision(), 1));
    }

    static String originalAuthor(BusinessContentRevision row) {
        return row.getRevisionKind() == null ? row.getCreatedBy() : row.getOriginalAuthorId();
    }
    static Instant originalAuthoredAt(BusinessContentRevision row) {
        return row.getRevisionKind() == null ? row.getCreatedAt() : row.getOriginalAuthoredAt();
    }
    private static String currentActorName() { return CurrentUserContext.currentUser().map(user -> user.username()).orElse(null); }
    private static String currentActor() { return CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null); }

    private BusinessContentRevision append(BusinessContentSource source, String text, String language,
            String author, String authorName, Instant authoredAt, String kind, int revision) {
        var row = new BusinessContentRevision();
        row.setTenantId(source.tenantId());
        row.setSourceKey(source.key());
        row.setModuleAlias(source.moduleAlias());
        row.setEntityKey(source.entityKey());
        row.setRecordId(source.recordId());
        row.setFieldKey(source.fieldKey());
        row.setRevision(revision);
        row.setOriginalText(text);
        row.setSourceLanguage(language);
        row.setOriginalAuthorId(author);
        row.setOriginalAuthorName(authorName);
        row.setRevisionAuthorName(currentActorName());
        row.setOriginalAuthoredAt(authoredAt);
        row.setRevisionKind(kind);
        EntityLifecycle.prepareInsert(row, Instant.now());
        row.setTenantId(source.tenantId());
        row.setCreatedBy(currentActor());
        revisions.insert(row);
        return row;
    }

    void purgeRecord(String tenantId, String moduleAlias, String entityKey, String recordId) {
        lockRecord(new BusinessContentSource(tenantId, moduleAlias, entityKey, recordId, ""));
        Criteria criteria = Criteria.of().eqNullable("tenantId", tenantId).eq("moduleAlias", moduleAlias)
                .eq("entityKey", entityKey).eq("recordId", recordId);
        while (true) {
            var batch = revisions.query(criteria, PageRequest.of(1, 200));
            if (batch.isEmpty()) return;
            if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Content cleanup requires a transaction");
            batch.forEach(row -> revisions.deleteById(row.getId()));
        }
    }

    /** Original writes, translation maintenance and final cleanup share the same owner partition. */
    static void lockRecord(BusinessContentSource source) {
        if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Business content requires a transaction");
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.lockMutationPartition("platform.business-content",
                new BusinessContentSource(source.tenantId(), source.moduleAlias(), source.entityKey(), source.recordId(), "").key());
    }

    static void checkExpected(BusinessContentRevision previous, SourceInput input) {
        if (input != null && input.expectedRevision() != null
                && (previous == null || !input.expectedRevision().equals(previous.getRevision())))
            throw new net.ximatai.muyun.spring.ability.OptimisticLockException("Business content source revision conflict");
        if (input != null && previous != null && input.expectedRevision() == null)
            throw new IllegalArgumentException("Source language changes require the reviewed source revision");
    }
}
