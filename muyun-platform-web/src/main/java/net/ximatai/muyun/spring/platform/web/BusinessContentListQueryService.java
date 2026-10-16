package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.BusinessContentQueryWebContext;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.platform.content.BusinessContentQueryService;
import org.springframework.stereotype.Component;

import java.util.*;

/** Adds reading-language ordering to an already authorized query; hydration keeps normal Service semantics. */
@Component
public final class BusinessContentListQueryService {
    private final BusinessContentQueryService content;
    private final RelationProjectionQueryExecutor executor;
    private final RelationProjectionDatabaseTypeProvider database;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public BusinessContentListQueryService(BusinessContentQueryService content,
                                           RelationProjectionQueryExecutor executor,
                                           RelationProjectionDatabaseTypeProvider database,
                                           org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations jdbc,
                                           com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.content = Objects.requireNonNull(content);
        this.executor = Objects.requireNonNull(executor);
        this.database = Objects.requireNonNull(database);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public boolean applies(CrudAbility<?> owner, Sort... sorts) {
        return BusinessContentQueryWebContext.language().isPresent() && sorts != null
                && Arrays.stream(sorts).anyMatch(sort -> content.fields(owner).contains(sort.getField()));
    }

    /** Caller supplies action, tenant, menu, navigation and parent scope before this boundary. */
    public <T extends EntityContract> PageResult<T> page(CrudAbility<T> owner,
            RelationProjectionSqlPlan source, Criteria authorizedCriteria, PageRequest page,
            RecordReadVisibility visibility, Sort... sorts) {
        String language = BusinessContentQueryWebContext.language().orElseThrow();
        if (source == null) source = RelationProjectionQueryPlanner.mainTableReadPlan(
                content.queryEntity(owner).orElseThrow(), database.databaseType(),
                RelationProjectionReadService.requiredMainFields(authorizedCriteria, sorts));
        var ordering = order(owner, source, language, sorts);
        var identities = executor.pageIdentities(ordering.plan(), authorizedCriteria, page, ordering.sorts());
        if (identities.getRecords().isEmpty()) return PageResult.of(List.of(), identities.getTotal(), page);
        Criteria selected = Criteria.copyOf(authorizedCriteria).in("id", identities.getRecords());
        List<T> records;
        if (visibility == RecordReadVisibility.RETAINED) {
            @SuppressWarnings("unchecked") var retained = (RecycleBinAbility<T>) owner;
            records = retained.pageRecycleBin(selected, PageRequest.of(1, identities.getRecords().size())).getRecords();
        } else records = owner.list(selected);
        Map<String, T> byId = new HashMap<>();
        records.forEach(record -> byId.put(record.getId(), record));
        return PageResult.of(identities.getRecords().stream().map(byId::get).filter(Objects::nonNull).toList(),
                identities.getTotal(), page);
    }

    /** Batch presentation only for fields actually present in this already-authorized output page. */
    public Map<String, Map<String, String>> readings(CrudAbility<?> owner, List<?> records, Set<String> allowedFields) {
        var language = BusinessContentQueryWebContext.language();
        if (language.isEmpty() || records == null || records.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Map<String, String>>();
        List<Map<?, ?>> output = records.stream().<Map<?, ?>>map(record -> mapper.convertValue(record, Map.class)).toList();
        for (String field : content.fields(owner)) {
            if (!allowedFields.contains(field)) continue;
            var ids = new LinkedHashSet<String>();
            var originals = new HashMap<String, String>();
            for (Map<?, ?> record : output) {
                Object id = record.get("id");
                Object text = record.containsKey("values") && record.get("values") instanceof Map<?, ?> values
                        ? values.get(field) : record.get(field);
                if (id instanceof String identity && text instanceof String raw) { ids.add(identity); originals.put(identity, raw); }
            }
            if (ids.isEmpty()) continue;
            var projection = content.readingProjection(owner, field, language.get(), "content_list_read_");
            var params = new LinkedHashMap<>(projection.getParams()); params.put("content_list_ids", ids);
            jdbc.query("select record_id, original_text, reading_text from (" + projection.getSql()
                    + ") reading where record_id in (:content_list_ids)", params, row -> {
                if (!Objects.equals(originals.get(row.getString("record_id")), row.getString("original_text"))) return;
                result.computeIfAbsent(row.getString("record_id"), ignored -> new LinkedHashMap<>())
                        .put(field, row.getString("reading_text"));
            });
        }
        return result;
    }

    Ordering order(CrudAbility<?> owner, RelationProjectionSqlPlan source, String language, Sort... sorts) {
        Set<String> contentFields = content.fields(owner);
        var fields = new LinkedHashSet<>(source.queryableFields());
        var sortable = new LinkedHashSet<>(source.sortableFields());
        var params = new LinkedHashMap<>(source.baseParams());
        var output = new ArrayList<Sort>();
        StringBuilder columns = new StringBuilder("select b.*");
        StringBuilder joins = new StringBuilder();
        int index = 0;
        for (Sort sort : sorts) {
            if (!source.sortableFields().contains(sort.getField()))
                throw new IllegalArgumentException("content sort field is not projected: " + sort.getField());
            if (!contentFields.contains(sort.getField())) { output.add(sort); continue; }
            String alias = "content_reading_" + index;
            String field = "__content_order_" + index++;
            var reading = content.readingProjection(owner, sort.getField(), language, alias + "_");
            for (String key : reading.getParams().keySet())
                if (params.containsKey(key)) throw new IllegalArgumentException("content query parameter collision");
            params.putAll(reading.getParams());
            columns.append(", lower(coalesce(").append(alias).append(".reading_text, b.")
                    .append(quote(sort.getField(), source)).append(")) as ").append(quote(field, source));
            joins.append(" left join (").append(reading.getSql()).append(") ").append(alias)
                    .append(" on ").append(alias).append(".record_id = b.").append(quote("id", source))
                    .append(" and (").append(alias).append(".tenant_id = b.").append(quote("tenantId", source))
                    .append(" or (").append(alias).append(".tenant_id is null and b.")
                    .append(quote("tenantId", source)).append(" is null))");
            fields.add(field); sortable.add(field);
            output.add(sort.getDirection() == SortDirection.DESC ? Sort.desc(field) : Sort.asc(field));
        }
        if (Arrays.stream(sorts).noneMatch(sort -> "id".equals(sort.getField()))) output.add(Sort.asc("id"));
        String sql = columns + " from (" + source.baseSql() + ") b" + joins;
        return new Ordering(new RelationProjectionSqlPlan(sql, params, fields, sortable, Set.of("id"),
                source.relationOutputFields(), source.databaseType(), source.projectionGraph()), output.toArray(Sort[]::new));
    }

    private static String quote(String name, RelationProjectionSqlPlan plan) {
        return RelationProjectionQueryPlanner.quote(name, plan.databaseType());
    }

    record Ordering(RelationProjectionSqlPlan plan, Sort[] sorts) { }
}
