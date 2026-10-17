package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.StaticContentFieldDefinitionCompiler;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;

/** Shared source storage for persisted immutable facts. Domain adapters own authorization and lifecycle. */
@Service
public final class BusinessContentFacts {
    private final BusinessContentStore originals;
    private final BusinessContentTranslationStore translations;
    private final BusinessContentGenerationStore generations;
    public BusinessContentFacts(BusinessContentStore originals, BusinessContentTranslationStore translations,
                                BusinessContentGenerationStore generations) {
        this.originals = originals; this.translations = translations; this.generations = generations;
    }

    /** Accept only a mapped producer declaration, after its adapter has authorized the live owning fact. */
    public BusinessContentService.FieldTarget target(String moduleAlias, EntityContract fact, String field) {
        PlatformNameRules.requireModuleAlias(moduleAlias);
        var table = fact.getClass().getAnnotation(Table.class);
        var definition = StaticContentFieldDefinitionCompiler.resolve(fact.getClass()).get(field);
        if (table == null || definition == null || fact.getId() == null)
            throw new IllegalArgumentException("A mapped business content fact is required");
        var source = new BusinessContentSource(fact.getTenantId(), moduleAlias, table.name(), fact.getId(), definition.key());
        return new BusinessContentService.FieldTarget(field, definition, source,
                BusinessContentFields.text(fact, field), fact.getVersion());
    }

    public BusinessContentService.SourceRevision record(BusinessContentService.FieldTarget target,
            String language, String authorId, String authorName, Instant occurredAt) {
        var tag = new ContentWriteContext.SourceInput(language, null).languageTag();
        return BusinessContentService.view(originals.captureFact(target.source(), target.text(), tag,
                authorId, authorName, occurredAt));
    }

    public BusinessContentService.Sources sources(BusinessContentService.FieldTarget target) {
        var latest = originals.latest(target.source());
        var matching = latest != null && java.util.Objects.equals(latest.getOriginalText(), target.text()) ? latest : null;
        return new BusinessContentService.Sources(target.recordVersion(), Map.of(target.fieldName(),
                new BusinessContentService.CurrentSource(target.content(), target.text(), latest == null ? null : latest.getRevision(),
                        matching == null ? null : BusinessContentService.view(matching))));
    }

    public java.util.List<BusinessContentService.SourceRevision> history(BusinessContentService.FieldTarget target, PageRequest page) {
        if (page.getLimit() > 200) throw new IllegalArgumentException("Content history page size must not exceed 200");
        return originals.history(target.source(), page).stream().map(BusinessContentService::view).toList();
    }

    /** Final owning-history deletion removes copies atomically; moving a fact to history does not. */
    public void purge(BusinessContentService.FieldTarget target) {
        var source = target.source();
        originals.purgeRecord(source.tenantId(), source.moduleAlias(), source.entityKey(), source.recordId());
        translations.purgeRecord(source.tenantId(), source.moduleAlias(), source.entityKey(), source.recordId());
        generations.purgeRecord(source.tenantId(), source.moduleAlias(), source.entityKey(), source.recordId());
    }
}
