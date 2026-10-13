package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;

import net.ximatai.muyun.spring.ability.event.*;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

/** Shared configuration translations. Original facts and publication commands stay producer-owned. */
@Service
public class ConfigurationTranslationService extends AbstractAbilityService<ConfigurationTranslation> {
    public static final String MODULE_ALIAS = "platform.localization";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final ObjectProvider<ConfiguredTextSourceProvider> sources;
    private final ObjectProvider<LanguageCatalog> catalogs;
    private final RuntimeEventPublisher events;

    public ConfigurationTranslationService(BaseDao<ConfigurationTranslation, String> dao,
            ObjectProvider<ConfiguredTextSourceProvider> sources, ObjectProvider<LanguageCatalog> catalogs,
            RuntimeEventPublisher events) {
        super(MODULE_ALIAS, ConfigurationTranslation.class, dao);
        this.sources = sources;
        this.catalogs = catalogs;
        this.events = events;
    }

    public LanguageCatalog languageCatalog() {
        return catalogs.getIfAvailable(() -> LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"));
    }

    public List<TextReference> currentSources(String moduleAlias) {
        String alias = PlatformNameRules.requireModuleAlias(moduleAlias);
        Map<String, TextReference> result = new LinkedHashMap<>();
        sources.orderedStream().forEach(provider -> provider.currentSources(alias).forEach(source -> {
            if (source.sourceKind() != TextReference.SourceKind.CONFIG) return;
            var previous = result.putIfAbsent(source.sourceId(), source);
            if (previous != null && !previous.equals(source)) throw new IllegalStateException("Conflicting configured text source: " + source.sourceId());
        }));
        return List.copyOf(result.values());
    }

    public List<ConfigurationTranslation> translations(String moduleAlias) {
        requireSystem();
        return shared(() -> list(Criteria.of().eq("moduleAlias", PlatformNameRules.requireModuleAlias(moduleAlias)), ALL));
    }

    public ConfigurationTranslation saveDraft(String moduleAlias, String sourceId, String sourceVersion,
            String languageTag, String text, Integer expectedVersion) {
        requireSystem();
        return inMutationTransaction(() -> {
            var source = currentSource(moduleAlias, sourceId, sourceVersion);
            String tag = requireLanguage(languageTag);
            requireText(text);
            lockMutation("translation", hash(sourceId) + "/" + sourceVersion + "/" + tag);
            var row = findOne(identity(source, tag));
            try (var ignored = ConfigurationTranslationMutationContext.open(ConfigurationTranslationMutationContext.Command.DRAFT)) {
                if (row == null) {
                    if (expectedVersion != null) throw failure("platform.translation.not-found", "翻译草稿不存在，请重新加载");
                    row = new ConfigurationTranslation();
                    row.setModuleAlias(PlatformNameRules.requireModuleAlias(moduleAlias));
                    row.setSourceId(source.sourceId());
                    row.setSourceHash(hash(source.sourceId()));
                    row.setSourceVersion(source.sourceVersion());
                    row.setOriginalText(source.originalText());
                    row.setLanguageTag(tag);
                    row.setDraftText(text);
                    insert(row);
                } else {
                    requireVersion(row, expectedVersion);
                    row.setDraftText(text);
                    update(row);
                }
            }
            return select(row.getId());
        });
    }

    public ConfigurationTranslation publish(String id, String sourceVersion, Integer expectedVersion) {
        requireSystem();
        return inMutationTransaction(() -> {
            var row = select(id);
            if (row == null) throw failure("platform.translation.not-found", "翻译草稿不存在，请重新加载");
            lockMutation("translation", hash(row.getSourceId()) + "/" + row.getSourceVersion() + "/" + row.getLanguageTag());
            requireVersion(row, expectedVersion);
            currentSource(row.getModuleAlias(), row.getSourceId(), sourceVersion);
            if (!row.getSourceVersion().equals(sourceVersion)) throw staleSource();
            requireLanguage(row.getLanguageTag());
            requireText(row.getDraftText());
            try (var ignored = ConfigurationTranslationMutationContext.open(ConfigurationTranslationMutationContext.Command.PUBLISH)) {
                row.setPublishedText(row.getDraftText());
                row.setPublishedRevision(Math.addExact(row.getPublishedRevision(), 1));
                update(row);
            }
            events.publishAfterCommit(RuntimeEvent.of(RuntimeEventType.CONFIG_TRANSLATION_PUBLISHED,
                    row.getModuleAlias(), null, null, null, null, true, "published configuration translation",
                    RuntimeMutationSource.SYSTEM, Map.of("sourceId", row.getSourceId(), "sourceVersion", row.getSourceVersion(),
                            "languageTag", row.getLanguageTag(), "publishedRevision", row.getPublishedRevision())));
            return select(id);
        });
    }

    /** One read for a presentation batch; drafts and other original versions are never visible. */
    public List<ConfigurationTranslation> published(Collection<TextReference> references) {
        var configured = references.stream().filter(source -> source.sourceKind() == TextReference.SourceKind.CONFIG).toList();
        if (configured.isEmpty()) return List.of();
        var hashes = configured.stream().map(source -> hash(source.sourceId())).distinct().toList();
        return shared(() -> list(Criteria.of().in("sourceHash", hashes), ALL)).stream()
                .filter(row -> row.getPublishedText() != null && configured.stream().anyMatch(source ->
                        source.sourceId().equals(row.getSourceId()) && source.sourceVersion().equals(row.getSourceVersion())
                                && source.originalText().equals(row.getOriginalText())))
                .toList();
    }

    @Override public void beforeInsert(ConfigurationTranslation row) {
        requireCommand();
        if (ConfigurationTranslationMutationContext.current() != ConfigurationTranslationMutationContext.Command.DRAFT ||
                row.getPublishedText() != null || !Objects.equals(row.getPublishedRevision(), 0)) throw immutableSource();
        validateIdentity(row);
    }

    @Override public void beforeUpdate(ConfigurationTranslation row) {
        requireCommand();
        var existing = select(row.getId());
        if (existing == null || !Objects.equals(existing.getModuleAlias(), row.getModuleAlias()) ||
                !Objects.equals(existing.getSourceId(), row.getSourceId()) || !Objects.equals(existing.getSourceHash(), row.getSourceHash()) ||
                !Objects.equals(existing.getSourceVersion(), row.getSourceVersion()) || !Objects.equals(existing.getLanguageTag(), row.getLanguageTag()) ||
                !Objects.equals(existing.getOriginalText(), row.getOriginalText()) || row.getTenantId() != null) throw immutableSource();
        if (ConfigurationTranslationMutationContext.current() != ConfigurationTranslationMutationContext.Command.PUBLISH &&
                (!Objects.equals(existing.getPublishedText(), row.getPublishedText()) ||
                        !Objects.equals(existing.getPublishedRevision(), row.getPublishedRevision()))) throw immutableSource();
        validateIdentity(row);
    }

    @Override public void beforeDelete(String id) { throw immutableSource(); }

    private void validateIdentity(ConfigurationTranslation row) {
        if (row.getTenantId() != null || !hash(row.getSourceId()).equals(row.getSourceHash())) throw immutableSource();
        var source = currentSource(row.getModuleAlias(), row.getSourceId(), row.getSourceVersion());
        if (!source.originalText().equals(row.getOriginalText())) throw immutableSource();
        requireLanguage(row.getLanguageTag());
        requireText(row.getDraftText());
    }
    private TextReference currentSource(String moduleAlias, String sourceId, String version) {
        return currentSources(moduleAlias).stream().filter(source -> source.sourceId().equals(sourceId) && source.sourceVersion().equals(version))
                .findFirst().orElseThrow(ConfigurationTranslationService::staleSource);
    }
    private String requireLanguage(String tag) {
        String canonical;
        try { canonical = LanguageCatalog.canonicalTag(tag); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw failure("platform.translation.language-unavailable", "目标语言未启用"); }
        if (languageCatalog().languages().stream().noneMatch(language -> language.enabled() && language.tag().equals(canonical)))
            throw failure("platform.translation.language-unavailable", "目标语言未启用");
        return canonical;
    }
    private static Criteria identity(TextReference source, String tag) {
        return Criteria.of().eq("sourceHash", hash(source.sourceId())).eq("sourceVersion", source.sourceVersion()).eq("languageTag", tag);
    }
    private static void requireVersion(ConfigurationTranslation row, Integer expected) {
        if (expected == null) throw failure("platform.translation.version-required", "保存或发布翻译需要草稿版本，请重新加载");
        if (!Objects.equals(row.getVersion(), expected)) throw new net.ximatai.muyun.spring.ability.OptimisticLockException("Translation version conflict: " + row.getId());
    }
    private static void requireText(String text) {
        if (text == null || text.isBlank()) throw failure("platform.translation.text-required", "译文不能为空");
    }
    private static void requireSystem() {
        if (!TenantContext.isSystem()) throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("Shared configuration translation commands require system scope");
    }
    private static void requireCommand() { requireSystem(); if (ConfigurationTranslationMutationContext.current() == null) throw immutableSource(); }
    private static net.ximatai.muyun.spring.ability.action.BusinessException failure(String code, String original) {
        return new net.ximatai.muyun.spring.ability.action.BusinessException(code, TextReference.code(code, original),
                net.ximatai.muyun.spring.ability.action.ActionMessageType.WARNING, Map.of());
    }
    private static RuntimeException staleSource() { return failure("platform.translation.source-stale", "原文已变化或来源不可用，请重新加载并复核译文"); }
    private static RuntimeException immutableSource() { return failure("platform.translation.command-required", "翻译来源与发布状态只能通过翻译维护命令修改"); }
    private static String hash(String value) { return TextReference.versionOf(value).substring("sha256-".length()); }
    private static <T> T shared(java.util.function.Supplier<T> work) {
        try (var ignored = TenantContext.system("shared configuration text presentation")) { return work.get(); }
    }
}
