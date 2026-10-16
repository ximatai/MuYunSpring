package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.platform.content.BusinessContentStore.OriginalVersion;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** Internal storage; callers must authorize the live owning record and declared field first. */
@Repository
class BusinessContentTranslationStore {
    private final BusinessContentTranslationDao translations;
    private final BusinessContentStore originals;

    BusinessContentTranslationStore(BusinessContentTranslationDao translations, BusinessContentStore originals) {
        this.translations = Objects.requireNonNull(translations);
        this.originals = Objects.requireNonNull(originals);
    }

    record Generation(String reference, String providerRequestId, String policyVersion, String contextVersion) {
        Generation {
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Generation reference is required");
            length(reference, 255); length(providerRequestId, 255); length(policyVersion, 128); length(contextVersion, 128);
        }
    }
    record State(Integer revision, BusinessContentTranslation draft, BusinessContentTranslation confirmed,
                 BusinessContentTranslation machine) {
        BusinessContentTranslation effective() { return confirmed == null ? machine : confirmed; }
    }

    State state(BusinessContentSource source, OriginalVersion sourceRevision, String language) {
        String tag = language(language);
        var head = latest(slot(source, sourceRevision, tag));
        if (head == null) return new State(null, null, null, null);
        int revision = head.getRevision();
        if ("REMOVED".equals(head.getKind())) return new State(revision, null, null, null);
        // A fixed head bounds every fact lookup. Reading never walks an entire append-only
        // history, nor combines a later concurrent write with an earlier optimistic head.
        var removed = latest(slot(source, sourceRevision, tag).lte("revision", revision).eq("kind", "REMOVED"));
        int boundary = removed == null ? 0 : removed.getRevision();
        var human = latest(slot(source, sourceRevision, tag).gt("revision", boundary).lte("revision", revision)
                .in("kind", List.of("DRAFT", "CONFIRMED")));
        var confirmed = human != null && "CONFIRMED".equals(human.getKind()) ? human
                : latest(slot(source, sourceRevision, tag).gt("revision", boundary).lte("revision", revision).eq("kind", "CONFIRMED"));
        var machine = "MACHINE".equals(head.getKind()) ? head
                : latest(slot(source, sourceRevision, tag).gt("revision", boundary).lte("revision", revision).eq("kind", "MACHINE"));
        return new State(revision, human != null && "DRAFT".equals(human.getKind()) ? human : null, confirmed, machine);
    }

    /** Maintenance-only reference; an explicit removal is never resurrected as a suggestion. */
    BusinessContentTranslation previousHuman(BusinessContentSource source, OriginalVersion current, String language) {
        return previousHuman(source, current, language, List.of("DRAFT", "CONFIRMED", "REMOVED"));
    }

    boolean humanReviewRequired(BusinessContentSource source, OriginalVersion current, String language) {
        // Ordinary readers may learn that a previously readable confirmation needs review,
        // but never learn whether somebody has a private maintenance draft.
        return previousHuman(source, current, language, List.of("CONFIRMED", "REMOVED")) != null;
    }

    boolean wasRemoved(BusinessContentSource source, OriginalVersion original, String language) {
        return latest(slot(source, original, language(language)).eq("kind", "REMOVED")) != null;
    }

    private BusinessContentTranslation previousHuman(BusinessContentSource source, OriginalVersion current, String language, List<String> kinds) {
        String tag = language(language);
        // Deleting this language also discards suggestions from earlier originals. A new
        // current draft is allowed, but must not bring those discarded suggestions back.
        if (current.revision() == 1 || latest(slot(source, current, tag).eq("kind", "REMOVED")) != null) return null;
        var row = translations.query(identity(source).lt("sourceRevision", current.revision())
                        .eq("languageTag", tag).in("kind", kinds),
                PageRequest.of(1, 1), Sort.desc("sourceRevision"), Sort.desc("revision")).stream().findFirst().orElse(null);
        return row == null || "REMOVED".equals(row.getKind()) ? null : row;
    }

    private BusinessContentTranslation latest(Criteria criteria) {
        return translations.query(criteria, PageRequest.of(1, 1), Sort.desc("revision")).stream().findFirst().orElse(null);
    }

    private static Criteria slot(BusinessContentSource source, OriginalVersion version, String language) {
        Objects.requireNonNull(version, "sourceRevision");
        return identity(source).eq("sourceRevisionId", version.revisionId()).eq("sourceRevision", version.revision()).eq("languageTag", language);
    }

    List<BusinessContentTranslation> history(BusinessContentSource source, OriginalVersion sourceRevision, String language, PageRequest page) {
        if (sourceRevision == null || page.getLimit() > 200) throw new IllegalArgumentException("Invalid translation history range");
        return translations.query(slot(source, sourceRevision, language(language)),
                page, Sort.desc("revision"));
    }

    BusinessContentTranslation saveDraft(BusinessContentSource source, OriginalVersion sourceRevision, String language,
                                         String text, Integer expectedRevision) {
        requireText(text);
        var current = prepare(source, sourceRevision, language, expectedRevision);
        return append(source, sourceRevision, language(language), current, "DRAFT", text, null, null);
    }

    BusinessContentTranslation confirm(BusinessContentSource source, OriginalVersion sourceRevision, String language,
                                       Integer expectedRevision, int reviewedDraftRevision) {
        var current = prepare(source, sourceRevision, language, expectedRevision);
        if (current.draft() == null || current.draft().getRevision() != reviewedDraftRevision)
            throw new OptimisticLockException("Reviewed business translation draft changed");
        return append(source, sourceRevision, language(language), current, "CONFIRMED",
                current.draft().getTranslatedText(), reviewedDraftRevision, null);
    }

    BusinessContentTranslation saveMachine(BusinessContentSource source, OriginalVersion sourceRevision, String language,
                                           String text, Integer expectedRevision, Generation generation) {
        requireText(text);
        Objects.requireNonNull(generation, "generation");
        var current = prepare(source, sourceRevision, language, expectedRevision);
        return append(source, sourceRevision, language(language), current, "MACHINE", text, null, generation);
    }

    BusinessContentTranslation remove(BusinessContentSource source, OriginalVersion sourceRevision, String language, Integer expectedRevision) {
        var current = prepare(source, sourceRevision, language, expectedRevision);
        if (current.revision() == null) throw new IllegalArgumentException("Translation does not exist");
        return append(source, sourceRevision, language(language), current, "REMOVED", null, null, null);
    }

    private State prepare(BusinessContentSource source, OriginalVersion revision, String language, Integer expected) {
        if (CurrentUserContext.currentUser().isEmpty()) throw new IllegalStateException("Translation mutation requires a trusted actor");
        BusinessContentStore.lockRecord(source);
        var original = originals.latest(source);
        if (original == null || !Objects.equals(original.getId(), revision.revisionId()) || original.getRevision() != revision.revision())
            throw new OptimisticLockException("Business translation original revision changed");
        if (original.getOriginalText() == null || original.getOriginalText().isBlank())
            throw new IllegalArgumentException("Translation requires a nonempty original");
        var state = state(source, revision, language);
        if (!Objects.equals(state.revision(), expected)) throw new OptimisticLockException("Business translation revision changed");
        return state;
    }

    private BusinessContentTranslation append(BusinessContentSource source, OriginalVersion original, String tag, State state,
            String kind, String text, Integer reviewedDraft, Generation generation) {
        var row = new BusinessContentTranslation();
        row.setSourceKey(source.key()); row.setModuleAlias(source.moduleAlias()); row.setEntityKey(source.entityKey());
        row.setRecordId(source.recordId()); row.setFieldKey(source.fieldKey()); row.setSourceRevision(original.revision());
        row.setSourceRevisionId(original.revisionId());
        row.setLanguageTag(tag); row.setRevision(state.revision() == null ? 1 : Math.addExact(state.revision(), 1));
        row.setKind(kind); row.setTranslatedText(text); row.setReviewedDraftRevision(reviewedDraft);
        if (generation != null) {
            row.setGenerationReference(generation.reference()); row.setProviderRequestId(generation.providerRequestId());
            row.setPolicyVersion(generation.policyVersion()); row.setContextVersion(generation.contextVersion());
        }
        var actor = CurrentUserContext.currentUser().orElseThrow();
        row.setAuthorName(actor.username());
        EntityLifecycle.prepareInsert(row, Instant.now());
        row.setTenantId(source.tenantId()); row.setCreatedBy(actor.userId());
        translations.insert(row);
        return row;
    }

    void purgeRecord(String tenantId, String module, String entity, String record) {
        var source = new BusinessContentSource(tenantId, module, entity, record, "");
        BusinessContentStore.lockRecord(source);
        Criteria owner = Criteria.of().eqNullable("tenantId", tenantId).eq("moduleAlias", module)
                .eq("entityKey", entity).eq("recordId", record);
        while (true) {
            var rows = translations.query(owner, PageRequest.of(1, 200));
            if (rows.isEmpty()) return;
            rows.forEach(row -> translations.deleteById(row.getId()));
        }
    }

    private static Criteria identity(BusinessContentSource source) {
        return Criteria.of().eq("sourceKey", source.key()).eqNullable("tenantId", source.tenantId())
                .eq("moduleAlias", source.moduleAlias()).eq("entityKey", source.entityKey())
                .eq("recordId", source.recordId()).eq("fieldKey", source.fieldKey());
    }
    private static String language(String value) {
        String tag = new ContentWriteContext.SourceInput(Objects.requireNonNull(value, "language"), null).languageTag();
        if (tag == null) throw new IllegalArgumentException("Target language is required");
        return tag;
    }
    private static void requireText(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Translation text is required");
    }
    private static void length(String value, int maximum) {
        if (value != null && value.length() > maximum) throw new IllegalArgumentException("Generation provenance is too long");
    }
}
