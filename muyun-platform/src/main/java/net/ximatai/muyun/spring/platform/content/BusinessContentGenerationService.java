package net.ximatai.muyun.spring.platform.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.content.ContentPolicy;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.ai.AiModelGateway;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.content.BusinessContentService.*;
import net.ximatai.muyun.spring.platform.content.BusinessContentStore.OriginalVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Durable, narrowly scoped automatic reading translations. No chat history, tools or ordinary business writes. */
@Service
public final class BusinessContentGenerationService {
    private static final Set<String> LANGUAGES = Set.of("zh-CN", "en", "de");
    private static final Set<String> RETRYABLE = Set.of("AI_MODEL_TIMEOUT", "AI_PROVIDER_UNAVAILABLE", "AI_CONCURRENCY_LIMIT");
    private static final Set<String> ACTIVE = Set.of("PENDING", "RUNNING", "RETRY");
    private final BusinessContentStore originals;
    private final BusinessContentTranslationStore translations;
    private final BusinessContentGenerationStore jobs;
    private final ObjectProvider<TrustedExecutionIdentityResolver> identities;
    private final ObjectProvider<BusinessContentGenerationScope> scopes;
    private final AiModelGateway gateway;
    private final BusinessContentTextTranslator translator;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectProvider<JobService> queue;

    public BusinessContentGenerationService(BusinessContentStore originals,
            BusinessContentTranslationStore translations, BusinessContentGenerationStore jobs,
            ObjectProvider<TrustedExecutionIdentityResolver> identities, ObjectProvider<BusinessContentGenerationScope> scopes,
            AiModelGateway gateway, ObjectMapper json, PlatformTransactionManager transactions, Clock clock, ObjectProvider<JobService> queue) {
        this.originals = Objects.requireNonNull(originals);
        this.translations = Objects.requireNonNull(translations); this.jobs = Objects.requireNonNull(jobs);
        this.identities = Objects.requireNonNull(identities); this.scopes = Objects.requireNonNull(scopes);
        this.gateway = Objects.requireNonNull(gateway); this.json = Objects.requireNonNull(json);
        this.translator = new BusinessContentTextTranslator(gateway, json);
        this.tx = new TransactionTemplate(Objects.requireNonNull(transactions)); this.clock = Objects.requireNonNull(clock);
        this.queue = Objects.requireNonNull(queue);
    }

    public record Status(String state, String reference, String problem, int attempts, Instant retryAt) { }

    /** Only trusted adapters construct execution scopes. No route means no executable generation row. */
    public Status request(BusinessContentGenerationScope.Reference scope, String moduleAlias, Location location,
                          String field, String language, boolean retry) {
        return request(scope, moduleAlias, location, field, language, retry, null, false);
    }

    /** Submission adapters already own a savepoint inside the original write transaction. */
    Status requestSubmitted(BusinessContentSubmissionScope.Submission submission, String language) {
        if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Submission requires the original transaction");
        return request(submission.scope(), submission.moduleAlias(), submission.location(), submission.field(), language,
                false, Objects.requireNonNull(submission.originalRevisionId()), true);
    }

    private Status request(BusinessContentGenerationScope.Reference scope, String moduleAlias, Location location,
                           String field, String language, boolean retry, String originalId, boolean transactional) {
        String tag = language(language); PlatformNameRules.requireModuleAlias(moduleAlias);
        return adapter(scope).withAccess(scope, moduleAlias, location, field, checked -> {
            Supplier<Status> work = () -> {
                var target = locked(checked, () -> adapter(scope).withAccess(scope, moduleAlias, location, field, Function.identity()));
                if (transactional && target.content().policy() != ContentPolicy.AUTO_TRANSLATE)
                    return state("DISABLED", "FIELD_POLICY");
                var original = original(target);
                if (originalId != null && (original == null || !originalId.equals(original.getId())))
                    return state("STALE", "SOURCE_OR_TRANSLATION_CHANGED");
                var eligibility = eligibility(target, original, tag);
                if (eligibility != null) return eligibility;
                var version = OriginalVersion.of(original);
                Integer expected = translations.state(target.source(), version, tag).revision();
                if (!gateway.isTextGenerationAvailable()) return state("UNAVAILABLE", "MODEL_UNAVAILABLE");
                String slot = slot(target.source(), version, tag, expected);
                var previous = jobs.latest(slot);
                if (previous != null && (ACTIVE.contains(previous.getStatus()) || !retry)) return view(previous);
                var resolver = identities.getIfAvailable();
                var identity = resolver == null ? null : resolver.capture(moduleAlias, PlatformAction.VIEW.code()).orElse(null);
                if (identity == null) return state("UNAVAILABLE", "IDENTITY_UNAVAILABLE");
                var route = Routing.capture();
                var request = new Request(moduleAlias, location, field, version.revisionId(), version.revision(), tag, expected,
                        identity, route, scope, BusinessContentTextTranslator.POLICY_VERSION);
                int sequence = previous == null ? 1 : Math.addExact(previous.getSequence(), 1);
                String payload = encode(request);
                return view(jobs.insert(target.source(), slot, sequence, hash(payload + ":" + sequence), payload, clock.instant()));
            };
            return transactional ? work.get() : tx.execute(status -> work.get());
        });
    }

    /** Authorized, read-only status lookup. It does not enqueue, retry or invoke a model. */
    public Status status(BusinessContentGenerationScope.Reference scope, String moduleAlias, Location location,
                         String field, String language) {
        String tag = language(language);
        return adapter(scope).withAccess(scope, moduleAlias, location, field, target -> {
            var original = original(target); var eligibility = eligibility(target, original, tag);
            if (eligibility != null) return eligibility;
            var version = OriginalVersion.of(original);
            var head = translations.state(target.source(), version, tag).revision();
            if (!gateway.isTextGenerationAvailable()) return state("UNAVAILABLE", "MODEL_UNAVAILABLE");
            var latest = jobs.latest(slot(target.source(), version, tag, head));
            if (latest != null) return view(latest);
            return state("MISSING", null);
        });
    }

    /** Operational convenience; execution and claims belong exclusively to the platform queue. */
    public boolean runOne() {
        if (TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Translation worker cannot run inside a transaction");
        var service = queue.getObject();
        return service.runOne(BusinessContentGenerationJob.CHANNEL, "content-operational-" + java.util.UUID.randomUUID());
    }

    JobResult execute(JobContext context) {
        var job = control(() -> jobs.find((String) context.parameters().get("generationId")));
        if (job == null) return JobResult.success("CANCELLED", Map.of());
        // Topic publication may have committed just before a node stopped, leaving its run unfinished.
        if (!ACTIVE.contains(job.getStatus())) {
            if ("FAILED".equals(job.getStatus())) throw new JobFailure(job.getProblemCode(), false);
            return JobResult.success(job.getProblemCode() == null ? job.getStatus() : job.getProblemCode(), Map.of());
        }
        context.checkCancelled();
        Request request;
        try { request = json.readValue(job.getRequestJson(), Request.class); }
        catch (Exception invalid) { finish(context, job, "FAILED", "INVALID_REQUEST", false); return JobResult.success("COMPLETE", Map.of()); }
        try {
            var result = withIdentity(request, () -> {
                var input = access(request, checked -> context.transaction(() -> BusinessContentStore.lockRecord(source(job)), () -> prepare(job, request, checked)));
                if (!gateway.isTextGenerationAvailable()) throw new Unavailable();
                return translator.translate(input.text(), input.language(), request.language());
            });
            withIdentity(request, () -> access(request, checked -> context.transaction(() -> BusinessContentStore.lockRecord(source(job)), () -> {
                context.checkCancelled();
                prepare(job, request, checked);
                translations.saveMachine(source(job), new OriginalVersion(request.originalId(), request.originalRevision()),
                        request.language(), result.text(), request.expectedRevision(), new BusinessContentTranslationStore.Generation(
                                job.getId(), result.providerRequestId(), request.policyVersion(), request.scope().version()));
                jobs.finish(jobs.find(job.getId()), "SUCCEEDED", null, clock.instant());
                return null;
            })));
        } catch (JobFailure failure) {
            throw failure;
        } catch (StaleWork | OptimisticLockException stale) {
            finish(context, job, "STALE", "SOURCE_OR_TRANSLATION_CHANGED", false);
        } catch (Unavailable unavailable) {
            finish(context, job, "UNAVAILABLE", "MODEL_UNAVAILABLE", false);
        } catch (InvalidExecution denied) {
            finish(context, job, "CANCELLED", denied.problem, false);
        } catch (BusinessContentTextTranslator.InvalidResult invalid) {
            finish(context, job, "FAILED", invalid.problem(), false);
        } catch (PlatformException failure) {
            if ("AI_MODEL_INTERRUPTED".equals(failure.code())) {
                finish(context, job, "FAILED", "AI_MODEL_INTERRUPTED", true);
                return JobResult.success("COMPLETE", Map.of());
            }
            boolean available;
            try { available = withIdentity(request, gateway::isTextGenerationAvailable); }
            catch (RuntimeException unavailable) { available = false; }
            if (!available) finish(context, job, "UNAVAILABLE", "MODEL_UNAVAILABLE", false);
            else finish(context, job, "FAILED", RETRYABLE.contains(failure.code()) ? failure.code() : "MODEL_REQUEST_FAILED",
                    RETRYABLE.contains(failure.code()));
        } catch (RuntimeException failure) {
            finish(context, job, "FAILED", "EXECUTION_FAILED", false);
        }
        var completed = control(() -> jobs.find(job.getId()));
        return JobResult.success(completed == null ? "CANCELLED" : completed.getProblemCode() == null
                ? completed.getStatus() : completed.getProblemCode(), Map.of());
    }

    /** Bounded topic retention; pending work is never silently discarded. */
    public int purgeExpired() {
        return control(() -> {
            Instant now = clock.instant(); int removed = 0;
            for (String id : jobs.expired(now)) removed += tx.execute(status -> {
                jobs.lock(id); return jobs.purgeExpired(id, now);
            });
            return removed;
        });
    }

    private Input prepare(BusinessContentGeneration job, Request request, FieldTarget checked) {
        var target = locked(checked, () -> access(request, Function.identity()));
        if (!source(job).equals(target.source()) || !BusinessContentTextTranslator.POLICY_VERSION.equals(request.policyVersion()))
            throw new StaleWork();
        var original = original(target);
        if (original == null || !request.originalId().equals(original.getId()) || original.getRevision() != request.originalRevision()
                || eligibility(target, original, request.language()) != null) throw new StaleWork();
        var version = OriginalVersion.of(original);
        if (!Objects.equals(request.expectedRevision(), translations.state(target.source(), version, request.language()).revision())) throw new StaleWork();
        return new Input(target.text(), original.getSourceLanguage());
    }

    private FieldTarget locked(FieldTarget target, Supplier<FieldTarget> reauthorize) {
        BusinessContentStore.lockRecord(target.source());
        // A concurrent save may move the owner to another workspace without changing its text.
        // Reopen the complete live entry after waiting for the owner lock, not only its VIEW permission.
        var current = reauthorize.get();
        if (!target.source().equals(current.source())) throw new OptimisticLockException("Business content field changed");
        return current;
    }
    private BusinessContentRevision original(FieldTarget target) {
        var row = originals.latest(target.source());
        return row != null && Objects.equals(row.getOriginalText(), target.text()) ? row : null;
    }
    private Status eligibility(FieldTarget target, BusinessContentRevision original, String language) {
        if (!target.content().policy().enabled()) return state("DISABLED", "FIELD_POLICY");
        if (target.text() == null || target.text().isBlank()) return state("ORIGINAL", null);
        if (original == null || original.getSourceLanguage() == null
                || Locale.forLanguageTag(original.getSourceLanguage()).getLanguage().isBlank())
            return state("SOURCE_UNKNOWN", "SOURCE_LANGUAGE_REQUIRED");
        if (sameLanguage(original.getSourceLanguage(), language)) return state("ORIGINAL", null);
        var version = OriginalVersion.of(original);
        if (translations.state(target.source(), version, language).effective() != null) return state("READY", null);
        if (translations.wasRemoved(target.source(), version, language)) return state("REMOVED", "EXPLICITLY_REMOVED");
        if (target.text().length() > BusinessContentTextTranslator.MAX_SOURCE_LENGTH) return state("UNSUPPORTED", "SOURCE_SIZE");
        return null;
    }
    private <T> T access(Request request, Function<FieldTarget, T> work) {
        try { return adapter(request.scope()).withAccess(request.scope(), request.moduleAlias(), request.location(), request.field(), target -> {
            try { return work.apply(target); }
            catch (PlatformAccessDeniedException | AuthenticationRequiredException denied) {
                throw new WorkFailure(new InvalidExecution("SCOPE_WITHDRAWN"));
            }
            catch (RuntimeException failure) { throw new WorkFailure(failure); }
        }); }
        catch (WorkFailure failure) { throw failure.failure; }
        catch (RuntimeException denied) { throw new InvalidExecution("SCOPE_WITHDRAWN"); }
    }
    private <T> T withIdentity(Request request, Supplier<T> work) {
        TrustedExecutionIdentityResolver.Identity identity;
        try {
            var resolver = identities.getIfAvailable();
            identity = resolver == null ? null : resolver.resolve(request.identity(), request.moduleAlias(), PlatformAction.VIEW.code()).orElse(null);
        } catch (RuntimeException denied) { throw new InvalidExecution("IDENTITY_WITHDRAWN"); }
        if (identity == null) throw new InvalidExecution("IDENTITY_WITHDRAWN");
        try (var actor = CurrentUserContext.use(identity.user()); var acting = identity.actingContext() == null
                ? ActingContextHolder.without() : ActingContextHolder.use(identity.actingContext()); var route = request.routing().open()) {
            return work.get();
        }
    }
    private void finish(JobContext context, BusinessContentGeneration job, String status, String problem, boolean retry) {
        if (retry) throw new JobFailure(problem, true);
        control(() -> context.transaction(() -> BusinessContentStore.lockRecord(source(job)), () -> {
            var current = jobs.find(job.getId());
            if (current != null) jobs.finish(current, status, problem, clock.instant());
            return null;
        }));
        if ("FAILED".equals(status)) throw new JobFailure(problem, false);
    }
    private <T> T control(Supplier<T> work) {
        try (var noActor = CurrentUserContext.without(); var noPrincipal = ActingContextHolder.without();
             var noAction = ActionExecutionContextHolder.without(); var scope = TenantContext.system("business content translation queue control")) {
            return work.get();
        }
    }
    private BusinessContentGenerationScope adapter(BusinessContentGenerationScope.Reference reference) {
        Objects.requireNonNull(reference, "scope");
        var matches = scopes.orderedStream().filter(scope -> reference.adapter().equals(scope.key())).toList();
        if (matches.size() != 1) throw new IllegalStateException("A single live content execution scope adapter is required");
        return matches.getFirst();
    }
    private String encode(Request request) {
        try { return json.writeValueAsString(request); }
        catch (Exception failure) { throw new IllegalStateException("Cannot persist business content request"); }
    }
    private static BusinessContentSource source(BusinessContentGeneration row) {
        return new BusinessContentSource(row.getTenantId(), row.getModuleAlias(), row.getEntityKey(), row.getRecordId(), row.getFieldKey());
    }
    private static String slot(BusinessContentSource source, OriginalVersion original, String language, Integer head) {
        return hash(source.key() + ":" + original.revisionId() + ":" + language + ":" + head + ":" + BusinessContentTextTranslator.POLICY_VERSION);
    }
    private static String hash(String value) { return TextReference.versionOf(value).substring("sha256-".length()); }
    private static Status state(String state, String problem) { return new Status(state, null, problem, 0, null); }
    private Status view(BusinessContentGeneration row) {
        var run = jobs.run(row);
        return new Status("SUCCEEDED".equals(row.getStatus()) ? "STALE" : row.getStatus(), row.getId(), row.getProblemCode(),
                run == null ? 0 : run.getAttempts(), "RETRY".equals(row.getStatus()) && run != null ? run.getAvailableAt() : null);
    }
    private static String language(String language) {
        if (language == null || !LANGUAGES.contains(language)) throw new IllegalArgumentException("Automatic reading language is not enabled");
        return language;
    }
    private static boolean sameLanguage(String source, String target) {
        return source.equalsIgnoreCase(target) || !target.contains("-")
                && Locale.forLanguageTag(source).getLanguage().equalsIgnoreCase(target);
    }
    private record Input(String text, String language) { }
    record Request(String moduleAlias, Location location, String field, String originalId, int originalRevision,
                   String language, Integer expectedRevision, TrustedExecutionIdentityResolver.Reference identity,
                   Routing routing, BusinessContentGenerationScope.Reference scope, String policyVersion) {
        Request {
            PlatformNameRules.requireModuleAlias(moduleAlias); Objects.requireNonNull(location, "location");
            if (field == null || field.isBlank() || field.length() > 128) throw new IllegalArgumentException("Invalid content field");
            new OriginalVersion(originalId, originalRevision); BusinessContentGenerationService.language(language);
            if (expectedRevision != null && expectedRevision < 1) throw new IllegalArgumentException("Invalid translation head");
            Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(routing, "routing");
            Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(policyVersion, "policyVersion");
        }
    }
    record Routing(String tenantId, boolean system) {
        static Routing capture() {
            if (!TenantContext.hasContext()) throw new IllegalStateException("Translation routing context is required");
            return new Routing(TenantContext.currentTenantId().orElse(null), TenantContext.isSystem());
        }
        TenantContext.Scope open() {
            if (system && tenantId == null) return TenantContext.system("restore original business content execution scope");
            if (!system && tenantId != null && !tenantId.isBlank()) return TenantContext.use(tenantId);
            throw new InvalidExecution("INVALID_ROUTING_SCOPE");
        }
    }
    private static final class StaleWork extends RuntimeException { }
    private static final class Unavailable extends RuntimeException { }
    private static final class WorkFailure extends RuntimeException {
        final RuntimeException failure;
        WorkFailure(RuntimeException failure) { this.failure = failure; }
    }
    private static final class InvalidExecution extends RuntimeException {
        final String problem;
        InvalidExecution(String problem) { this.problem = problem; }
    }
}
