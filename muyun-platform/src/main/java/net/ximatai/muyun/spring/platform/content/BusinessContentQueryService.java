package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.StaticEntityDefinitionCompiler;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordDao;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Expands validated user text filters to current readable translations. The caller must apply
 * business, tenant, navigation and permission scope afterwards, without expanding that scope.
 * This is a predicate on the existing business query, so its count, paging and export stay in SQL.
 */
@Service
public final class BusinessContentQueryService {
    private static final String BLANK_CHARACTERS = java.util.stream.IntStream.rangeClosed(0, Character.MAX_CODE_POINT)
            .filter(Character::isWhitespace).collect(StringBuilder::new, StringBuilder::appendCodePoint,
                    StringBuilder::append).toString();
    private final DBInfo.Type databaseType;
    private final CriteriaSqlCompiler compiler = new CriteriaSqlCompiler();
    private static final ClassValue<Optional<EntityDefinition>> STATIC = new ClassValue<>() {
        @Override protected Optional<EntityDefinition> computeValue(Class<?> type) {
            if (net.ximatai.muyun.spring.dynamic.metadata.StaticContentFieldDefinitionCompiler.resolve(type).isEmpty())
                return Optional.empty();
            return Optional.of(new StaticEntityDefinitionCompiler().compile("main", "Business content", type));
        }
    };

    public BusinessContentQueryService(IDatabaseOperations<?> operations) {
        databaseType = Objects.requireNonNull(operations).getDBInfo().getDatabaseType();
    }

    public Criteria expandUserText(CrudAbility<?> owner, Criteria criteria) {
        if (criteria == null || criteria.isEmpty()) return criteria;
        EntityDefinition entity = entity(owner);
        if (entity == null) return criteria;
        Map<String, FieldDefinition> fields = contentFields(entity);
        if (fields.isEmpty()) return criteria;
        return Criteria.of().andGroup(expand(criteria.getRoot(), owner.getModuleAlias(), entityKey(owner, entity), entity, fields, false));
    }

    private CriteriaGroup expand(CriteriaGroup input, String module, String entityKey, EntityDefinition entity,
                                 Map<String, FieldDefinition> fields, boolean coverageScope) {
        CriteriaGroup result = CriteriaGroup.create();
        for (var entry : input.getEntries()) {
            CriteriaGroup child;
            if (entry.getNode() instanceof CriteriaGroup group) {
                child = expand(group, module, entityKey, entity, fields, coverageScope);
            } else if (entry.getNode() instanceof CriteriaClause clause) {
                child = CriteriaGroup.create().and(clause.getField(), clause.getOperator(), clause.getValues().toArray());
                FieldDefinition field = fields.get(clause.getField());
                if (field != null && searchable(clause.getOperator()) && coverageScope) {
                    // All persisted business identities are non-null. Unlike an empty group,
                    // this remains TRUE inside an OR, so coverage never drops untranslated rows.
                    child = CriteriaGroup.create().isNotNull("id");
                } else if (field != null && searchable(clause.getOperator())) {
                    boolean excluded = clause.getOperator() == CriteriaOperator.NE || clause.getOperator() == CriteriaOperator.NOT_IN;
                    SqlSubQuery translated = matches(module, entityKey, entity, field, clause);
                    if (excluded) child.notInSubQuery("id", translated);
                    else child.orGroup(group -> group.inSubQuery("id", translated));
                }
            } else {
                throw new IllegalArgumentException("Unsupported validated business query node");
            }
            Enum<?> join = entry.getJoin();
            if ("OR".equals(join.name())) result.orGroup(child);
            else result.andGroup(child);
        }
        return result;
    }

    private boolean searchable(CriteriaOperator operator) {
        return switch (operator) {
            case EQ, NE, LIKE, LIKE_IGNORE_CASE, IN, NOT_IN -> true;
            default -> false;
        };
    }

    private SqlSubQuery matches(String module, String entityKey, EntityDefinition entity,
                                FieldDefinition field, CriteriaClause clause) {
        CriteriaOperator operator = switch (clause.getOperator()) {
            case NE -> CriteriaOperator.EQ;
            case NOT_IN -> CriteriaOperator.IN;
            default -> clause.getOperator();
        };
        CompiledCriteria text = compiler.compile(Criteria.of().and("translatedText", operator,
                clause.getValues().toArray()), ignored -> "translated_text", databaseType);
        Map<String, Object> params = new LinkedHashMap<>(text.getParams());
        return translatedRecords(module, entityKey, entity, field, null, "content_", "r.record_id",
                text.getSql().replace(quote("translated_text"), "t." + quote("translated_text")), params);
    }

    /** Safe over-approximation of user scope for detecting rows a translated text filter could miss. */
    public Criteria coverageUserScope(CrudAbility<?> owner, Criteria criteria) {
        if (criteria == null || criteria.isEmpty()) return criteria;
        EntityDefinition entity = entity(owner);
        if (entity == null) return criteria;
        Map<String, FieldDefinition> fields = contentFields(entity);
        if (fields.isEmpty()) return criteria;
        return Criteria.of().andGroup(expand(criteria.getRoot(), owner.getModuleAlias(), entityKey(owner, entity),
                entity, fields, true));
    }

    /** Immutable authoritative entity facts for the platform SQL read adapter. */
    public Optional<EntityDefinition> queryEntity(CrudAbility<?> owner) { return Optional.ofNullable(entity(owner)); }

    public Set<String> fields(CrudAbility<?> owner) {
        EntityDefinition entity = entity(owner);
        return entity == null ? Set.of() : Set.copyOf(contentFields(entity).keySet());
    }

    /** SQL adapter port: one current effective translated text per business row and target language. */
    public SqlSubQuery readingProjection(CrudAbility<?> owner, String fieldName, String language, String prefix) {
        if (prefix == null || !prefix.matches("[A-Za-z_][A-Za-z0-9_]{0,31}"))
            throw new IllegalArgumentException("Invalid content query parameter namespace");
        EntityDefinition entity = entity(owner);
        FieldDefinition field = entity == null ? null : contentFields(entity).get(fieldName);
        if (field == null) throw new IllegalArgumentException("Undeclared business content query field");
        return translatedRecords(owner.getModuleAlias(), entityKey(owner, entity), entity, field,
                language(language), prefix, "r.record_id, r.tenant_id, r.original_text, t.translated_text as reading_text", null, Map.of());
    }

    /** Missing current reading in at least one declared query field; caller still owns the full authorized scope. */
    public Criteria missingReadingCriteria(CrudAbility<?> owner, Set<String> queryFields, String language) {
        EntityDefinition entity = entity(owner);
        if (entity == null || queryFields == null || queryFields.isEmpty()) return Criteria.of().isNull("id");
        String tag = language(language);
        Criteria missing = Criteria.of().isNull("id");
        int index = 0;
        for (var entry : contentFields(entity).entrySet()) {
            if (!queryFields.contains(entry.getKey())) continue;
            FieldDefinition field = entry.getValue();
            String prefix = "content_missing_" + index++ + "_";
            Map<String, Object> params = new LinkedHashMap<>();
            params.put(prefix + "module", owner.getModuleAlias()); params.put(prefix + "entity", entityKey(owner, entity));
            params.put(prefix + "field", field.content().key()); params.put(prefix + "language", tag);
            String target = ":" + prefix + "language";
            String sql = "select b." + quote("id") + " from " + table(entity) + " b where b."
                    + quote(field.columnName()) + " is not null and " + nonblank("b." + quote(field.columnName()), prefix, params)
                    + " and not exists (select 1 from platform_business_content_revision r where r.record_id = b." + quote("id")
                    + " and (r.tenant_id = b." + quote("tenant_id") + " or (r.tenant_id is null and b." + quote("tenant_id") + " is null))"
                    + " and " + originalScope(field, prefix)
                    + " and ((" + sameLanguage(target) + ") or " + effectiveReadingId(target) + " is not null))";
            missing.orGroup(group -> group.inSubQuery("id", SqlSubQuery.of(sql, params)));
        }
        return missing;
    }

    private SqlSubQuery translatedRecords(String module, String entityKey, EntityDefinition entity,
                                           FieldDefinition field, String language, String prefix,
                                           String columns, String textPredicate, Map<String, Object> textParams) {
        Map<String, Object> params = new LinkedHashMap<>(textParams);
        params.put(prefix + "module", module); params.put(prefix + "entity", entityKey);
        params.put(prefix + "field", field.content().key());
        if (language != null) params.put(prefix + "language", language);
        // The same effective slot governs search, sorting and readiness. A private draft never
        // masks a confirmation, while a later explicit removal excludes every earlier result.
        String sql = "select " + columns + " " + originalSource(entity)
                + " join platform_business_content_translation t on " + (language == null ? slot("t")
                    : "t.id = " + effectiveReadingId(":" + prefix + "language"))
                + " where " + originalScope(field, prefix)
                + " and (r.source_language is null or not (" + sameLanguage("t.language_tag") + "))"
                + (language == null ? " and " + effectiveTranslation() : "");
        if (language != null) sql += " and t.language_tag = :" + prefix + "language";
        if (textPredicate != null) sql += " and (" + textPredicate + ")";
        return SqlSubQuery.of(sql, params);
    }

    private static String effectiveTranslation() {
        return "t.kind in ('CONFIRMED', 'MACHINE') and t.id = " + effectiveReadingId("t.language_tag");
    }

    private static String effectiveReadingId(String target) {
        // Each kind has its own indexed head. A confirmation must not require sorting every
        // later machine refresh in this append-only slot merely to retain human priority.
        return "coalesce(" + latestReadingId(target, "CONFIRMED") + ", " + latestReadingId(target, "MACHINE") + ")";
    }

    private static String latestReadingId(String target, String kind) {
        return "(select effective.id from platform_business_content_translation effective"
                + " where " + slot("effective") + " and effective.language_tag = " + target
                + " and effective.kind = '" + kind + "'"
                + " and effective.revision > coalesce((select max(removed.revision) from platform_business_content_translation removed where " + slot("removed")
                + " and removed.language_tag = " + target + " and removed.kind = 'REMOVED'), 0)"
                + " order by effective.revision desc limit 1)";
    }

    private String nonblank(String column, String prefix, Map<String, Object> params) {
        // Match String.isBlank at the write boundary, independent of database locale or its
        // broader/narrower regex whitespace classes (for example nonbreaking spaces).
        if (databaseType != DBInfo.Type.MYSQL) {
            params.put(prefix + "blank", BLANK_CHARACTERS);
            return "btrim(" + column + ", :" + prefix + "blank) <> ''";
        }
        String stripped = column;
        int[] characters = BLANK_CHARACTERS.codePoints().toArray();
        for (int index = 0; index < characters.length; index++) {
            String key = prefix + "blank_" + index;
            params.put(key, Character.toString(characters[index]));
            stripped = "replace(" + stripped + ", :" + key + ", '')";
        }
        return stripped + " <> ''";
    }

    private String originalSource(EntityDefinition entity) {
        return "from platform_business_content_revision r join " + table(entity) + " b on b." + quote("id")
                + " = r.record_id and (b." + quote("tenant_id") + " = r.tenant_id or (b." + quote("tenant_id")
                + " is null and r.tenant_id is null))";
    }

    private String originalScope(FieldDefinition field, String prefix) {
        return "r.module_alias = :" + prefix + "module and r.entity_key = :" + prefix
                + "entity and r.field_key = :" + prefix + "field and r.original_text = b." + quote(field.columnName())
                + " and r.id = (select newest.id from platform_business_content_revision newest"
                + " where newest.source_key = r.source_key order by newest.revision desc limit 1)";
    }

    private static String sameLanguage(String target) {
        return "lower(r.source_language) = lower(" + target + ") or (" + target + " not like '%-%'"
                + " and lower(r.source_language) like concat(lower(" + target + "), '-%'))";
    }

    private String table(EntityDefinition entity) { return quote(entity.schemaName()) + "." + quote(entity.tableName()); }

    private static EntityDefinition entity(CrudAbility<?> owner) {
        return owner.getDao() instanceof DynamicRecordDao dao ? dao.getEntity()
                : STATIC.get(Objects.requireNonNull(owner.modelClass(), "Business content query model")).orElse(null);
    }

    private static String entityKey(CrudAbility<?> owner, EntityDefinition entity) {
        return owner.getDao() instanceof DynamicRecordDao ? entity.alias() : entity.tableName();
    }

    private static Map<String, FieldDefinition> contentFields(EntityDefinition entity) {
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        entity.fields().stream().filter(field -> field.content().enabled()).forEach(field -> fields.put(field.fieldName(), field));
        return fields;
    }

    private static String language(String language) {
        String value = new net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput(language, null).languageTag();
        if (value == null) throw new IllegalArgumentException("Content query reading language is required");
        return value;
    }

    private static String slot(String alias) {
        return alias + ".source_key = r.source_key and " + alias + ".source_revision_id = r.id and "
                + alias + ".source_revision = r.revision and " + alias + ".module_alias = r.module_alias and "
                + alias + ".entity_key = r.entity_key and " + alias + ".record_id = r.record_id and "
                + alias + ".field_key = r.field_key and (" + alias + ".tenant_id = r.tenant_id or ("
                + alias + ".tenant_id is null and r.tenant_id is null))";
    }

    private String quote(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid business content SQL identifier");
        return databaseType == DBInfo.Type.MYSQL ? "`" + identifier + "`" : "\"" + identifier + "\"";
    }
}
