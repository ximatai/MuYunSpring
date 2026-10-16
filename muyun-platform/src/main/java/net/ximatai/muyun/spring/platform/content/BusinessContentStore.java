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

    List<BusinessContentRevision> history(BusinessContentSource source, PageRequest page) {
        return revisions.query(Criteria.of().eq("sourceKey", source.key()).eqNullable("tenantId", source.tenantId())
                .eq("moduleAlias", source.moduleAlias()).eq("entityKey", source.entityKey())
                .eq("recordId", source.recordId()).eq("fieldKey", source.fieldKey()), page, Sort.desc("revision"));
    }

    void capture(BusinessContentSource source, String text, SourceInput input) {
        if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Business content requires a transaction");
        var previous = latest(source);
        checkExpected(previous, input);
        boolean sameText = previous != null && Objects.equals(previous.getOriginalText(), text);
        String language = input != null ? input.languageTag() : sameText ? previous.getSourceLanguage() : null;
        if (sameText && Objects.equals(previous.getSourceLanguage(), language)) return;
        var row = new BusinessContentRevision();
        row.setTenantId(source.tenantId());
        row.setSourceKey(source.key());
        row.setModuleAlias(source.moduleAlias());
        row.setEntityKey(source.entityKey());
        row.setRecordId(source.recordId());
        row.setFieldKey(source.fieldKey());
        row.setRevision(previous == null ? 1 : Math.addExact(previous.getRevision(), 1));
        row.setOriginalText(text);
        row.setSourceLanguage(language);
        EntityLifecycle.prepareInsert(row, Instant.now());
        // Source ownership and actor are server facts; never accept client-supplied audit values.
        row.setTenantId(source.tenantId());
        row.setCreatedBy(CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null));
        revisions.insert(row);
    }

    void purgeRecord(String tenantId, String moduleAlias, String entityKey, String recordId) {
        Criteria criteria = Criteria.of().eqNullable("tenantId", tenantId).eq("moduleAlias", moduleAlias)
                .eq("entityKey", entityKey).eq("recordId", recordId);
        while (true) {
            var batch = revisions.query(criteria, PageRequest.of(1, 200));
            if (batch.isEmpty()) return;
            if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Content cleanup requires a transaction");
            batch.forEach(row -> revisions.deleteById(row.getId()));
        }
    }

    static void checkExpected(BusinessContentRevision previous, SourceInput input) {
        if (input != null && input.expectedRevision() != null
                && (previous == null || !input.expectedRevision().equals(previous.getRevision())))
            throw new net.ximatai.muyun.spring.ability.OptimisticLockException("Business content source revision conflict");
        if (input != null && previous != null && input.expectedRevision() == null)
            throw new IllegalArgumentException("Source language changes require the reviewed source revision");
    }
}
