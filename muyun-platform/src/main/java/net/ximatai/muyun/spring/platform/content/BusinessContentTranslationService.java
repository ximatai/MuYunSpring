package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.content.BusinessContentService.Location;
import net.ximatai.muyun.spring.platform.content.BusinessContentService.FieldTarget;
import net.ximatai.muyun.spring.platform.content.BusinessContentStore.OriginalVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Public content facade: original authorization, reading and translation maintenance remain separate. */
@Service
public final class BusinessContentTranslationService {
    private final BusinessContentService content;
    private final BusinessContentStore originals;
    private final BusinessContentTranslationStore translations;
    private final TransactionTemplate transactions;

    public BusinessContentTranslationService(BusinessContentService content, BusinessContentStore originals,
            BusinessContentTranslationStore translations, PlatformTransactionManager transactions) {
        this.content = Objects.requireNonNull(content); this.originals = Objects.requireNonNull(originals);
        this.translations = Objects.requireNonNull(translations);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
    }

    public record TranslationRevision(int revision, String kind, String text, String authorId, String authorName,
                                      Instant createdAt, Integer reviewedDraftRevision, String generationReference) { }
    public record ReadingField(String originalText, BusinessContentService.SourceRevision source,
                               String text, String actualLanguage, boolean originalFallback,
                               TranslationRevision translation, boolean humanReviewRequired) { }
    public record Reading(Integer recordVersion, String language, Map<String, ReadingField> fields) {
        public Reading { fields = Map.copyOf(fields); }
    }
    public record PreviousHuman(BusinessContentService.SourceRevision original, TranslationRevision translation) { }
    public record Maintenance(BusinessContentService.SourceRevision original, Integer expectedRevision,
                              TranslationRevision draft, TranslationRevision confirmed, TranslationRevision machine,
                              PreviousHuman previousHuman) { }
    public record Change(String sourceRevisionId, int sourceRevision, Integer expectedRevision,
                         String text, Integer reviewedDraftRevision) {
        public Change {
            new OriginalVersion(sourceRevisionId, sourceRevision);
            if (expectedRevision != null && expectedRevision < 1) throw new IllegalArgumentException("Invalid translation revision");
        }
    }

    /** No writes, model calls or draft disclosure. Raw values are never changed by this display projection. */
    public Reading read(CrudAbility<?> parent, Location location, String language) {
        String targetLanguage = language(language);
        var owned = content.owned(parent, location, PlatformAction.VIEW);
        Map<String, ReadingField> result = new LinkedHashMap<>();
        content.readableFields(owned.ability(), owned.record()).forEach((field, definition) ->
            result.put(field, reading(new FieldTarget(field, definition,
                    BusinessContentSaveLifecycleListener.source(owned.ability(), owned.record(), definition.key()),
                    BusinessContentFields.text(owned.record(), field), owned.record().getVersion()), targetLanguage)));
        return new Reading(owned.record().getVersion(), targetLanguage, result);
    }

    /** The entry has already authorized this immutable raw snapshot; no model work occurs during projection. */
    public Reading read(FieldTarget target, String language) {
        String tag = language(language);
        return new Reading(target.recordVersion(), tag, Map.of(target.fieldName(), reading(target, tag)));
    }
    private ReadingField reading(FieldTarget target, String language) {
        var original = matchingOriginal(target.source(), target.text());
        var effective = original == null || sameLanguage(original.getSourceLanguage(), language) ? null
                : translations.state(target.source(), OriginalVersion.of(original), language).effective();
        boolean reviewRequired = original != null && !sameLanguage(original.getSourceLanguage(), language)
                && (effective == null || !"CONFIRMED".equals(effective.getKind()))
                && translations.humanReviewRequired(target.source(), OriginalVersion.of(original), language);
        return new ReadingField(target.text(), original == null ? null : BusinessContentService.view(original),
                effective == null ? target.text() : effective.getTranslatedText(),
                effective == null ? original == null ? null : original.getSourceLanguage() : language,
                effective == null && (original == null || !sameLanguage(original.getSourceLanguage(), language)),
                view(effective), reviewRequired);
    }

    /** Opening maintenance explicitly establishes a truthful unknown-author baseline for legacy raw text. */
    public Maintenance prepare(CrudAbility<?> parent, Location location, String field, String language, PlatformAction action) {
        return prepare(requested -> content.target(parent, location, field, requested), language, action);
    }
    public Maintenance prepare(BusinessContentFieldAccess access, String language, PlatformAction action) {
        requireMaintenanceAction(action);
        return locked(access, action, target -> {
            originals.capture(target.source(), target.text(), null, new BusinessContentStore.Baseline(target.text()));
            var original = originals.latest(target.source());
            return maintenance(target, original, language(language));
        });
    }

    public Maintenance saveDraft(CrudAbility<?> parent, Location location, String field, String language, Change change) {
        return saveDraft(action -> content.target(parent, location, field, action), language, change);
    }
    public Maintenance saveDraft(BusinessContentFieldAccess access, String language, Change change) {
        return change(access, language, change, PlatformAction.CONTENT_EDIT,
                (target, version, tag) -> translations.saveDraft(target.source(), version, tag, change.text(), change.expectedRevision()));
    }
    public Maintenance confirm(CrudAbility<?> parent, Location location, String field, String language, Change change) {
        return confirm(action -> content.target(parent, location, field, action), language, change);
    }
    public Maintenance confirm(BusinessContentFieldAccess access, String language, Change change) {
        if (change.reviewedDraftRevision() == null || change.reviewedDraftRevision() < 1)
            throw new IllegalArgumentException("The reviewed draft revision is required");
        return change(access, language, change, PlatformAction.CONTENT_CONFIRM,
                (target, version, tag) -> translations.confirm(target.source(), version, tag,
                        change.expectedRevision(), change.reviewedDraftRevision()));
    }
    public Maintenance remove(CrudAbility<?> parent, Location location, String field, String language, Change change) {
        return remove(action -> content.target(parent, location, field, action), language, change);
    }
    public Maintenance remove(BusinessContentFieldAccess access, String language, Change change) {
        return change(access, language, change, PlatformAction.CONTENT_EDIT,
                (target, version, tag) -> translations.remove(target.source(), version, tag, change.expectedRevision()));
    }

    /** Historical drafts require maintenance rights too, and remain scoped to the live owning field. */
    public List<TranslationRevision> history(CrudAbility<?> parent, Location location, String field, String language,
            String sourceRevisionId, int sourceRevision, PageRequest page, PlatformAction action) {
        return history(requested -> content.target(parent, location, field, requested), language,
                sourceRevisionId, sourceRevision, page, action);
    }
    public List<TranslationRevision> history(BusinessContentFieldAccess access, String language,
            String sourceRevisionId, int sourceRevision, PageRequest page, PlatformAction action) {
        requireMaintenanceAction(action);
        var target = access.require(action);
        return translations.history(target.source(), new OriginalVersion(sourceRevisionId, sourceRevision), language(language), page)
                .stream().map(BusinessContentTranslationService::view).toList();
    }

    private Maintenance change(BusinessContentFieldAccess access, String language, Change change,
                               PlatformAction action, TranslationMutation mutation) {
        Objects.requireNonNull(change, "change");
        String tag = language(language);
        return locked(access, action, target -> {
            var original = matchingOriginal(target.source(), target.text());
            if (original == null || !original.getId().equals(change.sourceRevisionId()) || original.getRevision() != change.sourceRevision())
                throw new OptimisticLockException("Reviewed business original changed; reopen translation maintenance");
            mutation.apply(target, OriginalVersion.of(original), tag);
            return maintenance(target, original, tag);
        });
    }
    private <T> T locked(BusinessContentFieldAccess access, PlatformAction action, Function<FieldTarget, T> work) {
        return transactions.execute(status -> {
            var checked = access.require(action);
            BusinessContentStore.lockRecord(checked.source());
            var current = access.require(action);
            if (!checked.source().equals(current.source())) throw new OptimisticLockException("Business content field changed");
            return work.apply(current);
        });
    }
    private Maintenance maintenance(FieldTarget target, BusinessContentRevision original, String tag) {
        var state = translations.state(target.source(), OriginalVersion.of(original), tag);
        var previous = translations.previousHuman(target.source(), OriginalVersion.of(original), tag);
        var previousOriginal = previous == null ? null : originals.find(target.source(),
                new OriginalVersion(previous.getSourceRevisionId(), previous.getSourceRevision()));
        var reference = previousOriginal == null ? null : new PreviousHuman(BusinessContentService.view(previousOriginal), view(previous));
        return new Maintenance(BusinessContentService.view(original), state.revision(), view(state.draft()), view(state.confirmed()), view(state.machine()), reference);
    }
    private BusinessContentRevision matchingOriginal(BusinessContentSource source, String text) {
        var original = originals.latest(source);
        return original != null && Objects.equals(original.getOriginalText(), text) ? original : null;
    }
    private static TranslationRevision view(BusinessContentTranslation row) {
        return row == null ? null : new TranslationRevision(row.getRevision(), row.getKind(), row.getTranslatedText(), row.getCreatedBy(),
                row.getAuthorName(), row.getCreatedAt(), row.getReviewedDraftRevision(), row.getGenerationReference());
    }
    private static String language(String value) {
        String tag = new ContentWriteContext.SourceInput(Objects.requireNonNull(value, "language"), null).languageTag();
        if (tag == null) throw new IllegalArgumentException("Target language is required");
        return tag;
    }
    private static boolean sameLanguage(String source, String target) {
        return source != null && (source.equalsIgnoreCase(target)
                || !target.contains("-") && Locale.forLanguageTag(source).getLanguage().equalsIgnoreCase(target));
    }
    private static void requireMaintenanceAction(PlatformAction action) {
        if (action != PlatformAction.CONTENT_EDIT && action != PlatformAction.CONTENT_CONFIRM)
            throw new IllegalArgumentException("Translation maintenance requires its own action");
    }
    @FunctionalInterface private interface TranslationMutation {
        void apply(FieldTarget target, OriginalVersion version, String language);
    }
}
