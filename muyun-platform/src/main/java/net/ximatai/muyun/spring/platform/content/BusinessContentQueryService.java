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
        boolean dynamic = owner.getDao() instanceof DynamicRecordDao;
        EntityDefinition entity = dynamic ? ((DynamicRecordDao) owner.getDao()).getEntity()
                : STATIC.get(Objects.requireNonNull(owner.modelClass(), "Business content query model")).orElse(null);
        if (entity == null) return criteria;
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        entity.fields().stream().filter(field -> field.content().enabled())
                .forEach(field -> fields.put(field.fieldName(), field));
        if (fields.isEmpty()) return criteria;
        String entityKey = dynamic ? entity.alias() : entity.tableName();
        return Criteria.of().andGroup(expand(criteria.getRoot(), owner.getModuleAlias(), entityKey, entity, fields));
    }

    private CriteriaGroup expand(CriteriaGroup input, String module, String entityKey, EntityDefinition entity,
                                 Map<String, FieldDefinition> fields) {
        CriteriaGroup result = CriteriaGroup.create();
        for (var entry : input.getEntries()) {
            CriteriaGroup child;
            if (entry.getNode() instanceof CriteriaGroup group) {
                child = expand(group, module, entityKey, entity, fields);
            } else if (entry.getNode() instanceof CriteriaClause clause) {
                child = CriteriaGroup.create().and(clause.getField(), clause.getOperator(), clause.getValues().toArray());
                FieldDefinition field = fields.get(clause.getField());
                if (field != null && searchable(clause.getOperator())) {
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
        params.put("content_module", module);
        params.put("content_entity", entityKey);
        params.put("content_field", field.content().key());
        String rawTable = quote(entity.schemaName()) + "." + quote(entity.tableName());
        // Selecting the latest original before checking raw equality prevents an older matching
        // revision from becoming current again after an untracked business write.
        String sql = """
                select r.record_id
                from platform_business_content_revision r
                join %s b on b.%s = r.record_id
                  and (b.%s = r.tenant_id or (b.%s is null and r.tenant_id is null))
                join platform_business_content_translation t on %s
                where r.module_alias = :content_module and r.entity_key = :content_entity
                  and r.field_key = :content_field and r.original_text = b.%s
                  and not exists (select 1 from platform_business_content_revision newer
                    where newer.source_key = r.source_key and newer.revision > r.revision)
                  and (r.source_language is null or not (
                    lower(r.source_language) = lower(t.language_tag)
                    or (t.language_tag not like '%%-%%'
                      and lower(r.source_language) like concat(lower(t.language_tag), '-%%'))))
                  and t.id = (select effective.id from platform_business_content_translation effective
                    where %s and effective.language_tag = t.language_tag
                      and effective.kind in ('CONFIRMED', 'MACHINE')
                      and not exists (select 1 from platform_business_content_translation removed
                        where %s and removed.language_tag = effective.language_tag
                          and removed.kind = 'REMOVED' and removed.revision >= effective.revision)
                    order by case when effective.kind = 'CONFIRMED' then 0 else 1 end,
                      effective.revision desc limit 1)
                  and (%s)
                """.formatted(rawTable, quote("id"), quote("tenant_id"), quote("tenant_id"),
                slot("t"), quote(field.columnName()), slot("effective"), slot("removed"),
                text.getSql().replace(quote("translated_text"), "t." + quote("translated_text")));
        return SqlSubQuery.of(sql, params);
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
