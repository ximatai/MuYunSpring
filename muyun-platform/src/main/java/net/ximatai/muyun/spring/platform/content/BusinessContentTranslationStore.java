package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.orm.*;
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

    State state(BusinessContentSource source, int sourceRevision, String language) {
        String tag = language(language);
        Integer head = null;
        BusinessContentTranslation draft = null, confirmed = null, machine = null;
        boolean humanSeen = false;
        for (int page = 1; ; page++) {
            var rows = history(source, sourceRevision, tag, PageRequest.of(page, 200));
            for (var row : rows) {
                if (head == null) head = row.getRevision();
                if ("REMOVED".equals(row.getKind())) return new State(head, draft, confirmed, machine);
                if ("DRAFT".equals(row.getKind()) || "CONFIRMED".equals(row.getKind())) {
                    if (!humanSeen) { humanSeen = true; if ("DRAFT".equals(row.getKind())) draft = row; }
                    if (confirmed == null && "CONFIRMED".equals(row.getKind())) confirmed = row;
                } else if (machine == null && "MACHINE".equals(row.getKind())) machine = row;
            }
            if (rows.size() < 200 || (humanSeen && confirmed != null && machine != null))
                return new State(head, draft, confirmed, machine);
        }
    }

    List<BusinessContentTranslation> history(BusinessContentSource source, int sourceRevision, String language, PageRequest page) {
        if (sourceRevision < 1 || page.getLimit() > 200) throw new IllegalArgumentException("Invalid translation history range");
        return translations.query(identity(source).eq("sourceRevision", sourceRevision).eq("languageTag", language(language)),
                page, Sort.desc("revision"));
    }

    BusinessContentTranslation saveDraft(BusinessContentSource source, int sourceRevision, String language,
                                         String text, Integer expectedRevision) {
        requireText(text);
        var current = prepare(source, sourceRevision, language, expectedRevision);
        return append(source, sourceRevision, language(language), current, "DRAFT", text, null, null);
    }

    BusinessContentTranslation confirm(BusinessContentSource source, int sourceRevision, String language,
                                       Integer expectedRevision, int reviewedDraftRevision) {
        var current = prepare(source, sourceRevision, language, expectedRevision);
        if (current.draft() == null || current.draft().getRevision() != reviewedDraftRevision)
            throw new OptimisticLockException("Reviewed business translation draft changed");
        return append(source, sourceRevision, language(language), current, "CONFIRMED",
                current.draft().getTranslatedText(), reviewedDraftRevision, null);
    }

    BusinessContentTranslation saveMachine(BusinessContentSource source, int sourceRevision, String language,
                                           String text, Integer expectedRevision, Generation generation) {
        requireText(text);
        Objects.requireNonNull(generation, "generation");
        var current = prepare(source, sourceRevision, language, expectedRevision);
        return append(source, sourceRevision, language(language), current, "MACHINE", text, null, generation);
    }

    BusinessContentTranslation remove(BusinessContentSource source, int sourceRevision, String language, Integer expectedRevision) {
        var current = prepare(source, sourceRevision, language, expectedRevision);
        if (current.revision() == null) throw new IllegalArgumentException("Translation does not exist");
        return append(source, sourceRevision, language(language), current, "REMOVED", null, null, null);
    }

    private State prepare(BusinessContentSource source, int revision, String language, Integer expected) {
        if (CurrentUserContext.currentUser().isEmpty()) throw new IllegalStateException("Translation mutation requires a trusted actor");
        BusinessContentStore.lockRecord(source);
        var original = originals.latest(source);
        if (original == null || original.getRevision() != revision)
            throw new OptimisticLockException("Business translation original revision changed");
        if (original.getOriginalText() == null || original.getOriginalText().isBlank())
            throw new IllegalArgumentException("Translation requires a nonempty original");
        var state = state(source, revision, language);
        if (!Objects.equals(state.revision(), expected)) throw new OptimisticLockException("Business translation revision changed");
        return state;
    }

    private BusinessContentTranslation append(BusinessContentSource source, int original, String tag, State state,
            String kind, String text, Integer reviewedDraft, Generation generation) {
        var row = new BusinessContentTranslation();
        row.setSourceKey(source.key()); row.setModuleAlias(source.moduleAlias()); row.setEntityKey(source.entityKey());
        row.setRecordId(source.recordId()); row.setFieldKey(source.fieldKey()); row.setSourceRevision(original);
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
