package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.*;
import net.ximatai.muyun.spring.common.content.ContentLanguageInputs;
import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.option.*;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.platform.content.*;
import net.ximatai.muyun.spring.platform.exchange.*;
import net.ximatai.muyun.spring.web.*;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * Shared delivery adapter. File metadata never supplies a service, tenant, policy or executable
 * action.
 */
@Component
public final class RecordExchangeWebSupport {
    static final String ATTRIBUTE = RecordExchangeWebSupport.class.getName();
    private final RecordExchangeService exchange;
    private final StaticModuleDefinitionCatalog statics;
    private final ModuleExecutionPlanCatalog plans;
    private final DynamicRecordService records;
    private final ActionEndpointContextResolver actions;
    private final ActionExecutionPolicyService policies;
    private final ObjectMapper mapper;
    private final RecordExchangeReferenceSupport references;
    private final BusinessContentQueryService contentQueries;
    private final ObjectProvider<OptionSourceRegistry> options;
    private final ObjectProvider<TextResolver> textResolvers;
    private final ObjectProvider<CrudWeb<?, ?>> controllers;

    public RecordExchangeWebSupport(
            RecordExchangeService exchange,
            StaticModuleDefinitionCatalog statics,
            ModuleExecutionPlanCatalog plans,
            DynamicRecordService records,
            ActionEndpointContextResolver actions,
            ActionExecutionPolicyService policies,
            ObjectMapper mapper,
            RecordExchangeReferenceSupport references,
            BusinessContentQueryService contentQueries,
            ObjectProvider<OptionSourceRegistry> options,
            ObjectProvider<TextResolver> textResolvers,
            ObjectProvider<CrudWeb<?, ?>> controllers) {
        this.exchange = Objects.requireNonNull(exchange);
        this.statics = Objects.requireNonNull(statics);
        this.plans = Objects.requireNonNull(plans);
        this.records = Objects.requireNonNull(records);
        this.actions = Objects.requireNonNull(actions);
        this.policies = Objects.requireNonNull(policies);
        this.mapper = Objects.requireNonNull(mapper);
        this.references = Objects.requireNonNull(references);
        this.contentQueries = Objects.requireNonNull(contentQueries);
        this.options = Objects.requireNonNull(options);
        this.textResolvers = Objects.requireNonNull(textResolvers);
        this.controllers = Objects.requireNonNull(controllers);
    }

    static RecordExchangeWebSupport current() {
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        var support = attributes.getRequest().getAttribute(ATTRIBUTE);
        if (!(support instanceof RecordExchangeWebSupport result))
            throw new IllegalStateException("Record exchange is unavailable");
        return result;
    }

    public byte[] template(CrudWeb<?, ?> web, RecordExchangeService.Export options) {
        return exchange.template(access(web, options.language()), options);
    }

    public RecordExchangeService.Preview preview(
            CrudWeb<?, ?> web, byte[] bytes, RecordExchangeService.Command command) {
        return exchange.preview(access(web), bytes, command);
    }

    public RecordExchangeService.Result execute(
            CrudWeb<?, ?> web, byte[] bytes, RecordExchangeService.Command command) {
        return exchange.execute(access(web), bytes, command);
    }

    public byte[] export(CrudWeb<?, ?> web, ExportRequest request) {
        var access = access(web, request.options().language());
        var selected =
                request.ids() == null || request.ids().isEmpty()
                        ? queryIds(web, request.query(), List.of())
                        : request.ids();
        if (request.ids() != null && !request.ids().isEmpty() && request.query() != null) {
            selected = queryIds(web, request.query(), request.ids());
        }
        var exportIds = selected;
        return WebOutputSupport.withViewSurface(
                net.ximatai.muyun.spring.ability.output.RecordOutputSurface.EXPORT,
                () -> exchange.export(access, exportIds, request.options()));
    }

    public RecordExchangeService.Preview compatibilityPreview(String module, byte[] bytes) {
        return preview(controller(module), bytes, null);
    }

    public RecordExchangeService.Result compatibilityImport(
            String module, byte[] bytes, RecordExchangeService.Command command) {
        var web = controller(module);
        var access = access(web);
        var reviewed = exchange.preview(access, bytes, command);
        return exchange.execute(
                access,
                bytes,
                new RecordExchangeService.Command(
                        reviewed.documentId(),
                        reviewed.previewDigest(),
                        command.sheets(),
                        command.confirmations(),
                        command.sourceLanguage(),
                        command.resolutionLanguage()));
    }

    /**
     * Legacy transport keeps its compiled query/page contract while file identity and projection
     * share the common engine.
     */
    public byte[] compatibilityExportQuery(
            net.ximatai.muyun.spring.platform.exchange.exporter.DynamicExportCommand query) {
        String module = query.descriptor().moduleAlias();
        var page =
                new PageRequest(
                        query.pageRequest().getOffset(),
                        Math.min(
                                query.pageRequest().getLimit(),
                                RecordExchangeService.MAX_ROWS + 1));
        var ids =
                records
                        .recordsForAction(
                                module, PlatformAction.EXPORT, "record-exchange-compatibility")
                        .list(
                                query.descriptor().mainEntityAlias(),
                                query.criteria(),
                                page,
                                query.sortArray())
                        .stream()
                        .map(DynamicRecord::getId)
                        .toList();
        return exchange.export(
                access(controller(module)),
                ids,
                new RecordExchangeService.Export("DATA", LanguageContext.currentTag(), "UTC"));
    }

    public void requireCompatibilityFileRead(String module, byte[] bytes) {
        exchange.requireFileRead(access(controller(module)), bytes);
    }

    private CrudWeb<?, ?> controller(String module) {
        var found =
                controllers
                        .orderedStream()
                        .filter(
                                web ->
                                        module.equals(web.webScopeName())
                                                && web.service().getDao()
                                                        instanceof DynamicRecordDao)
                        .toList();
        if (found.size() != 1)
            throw new IllegalStateException("The standard module exchange adapter is unavailable");
        return found.getFirst();
    }

    public record ExportRequest(
            List<String> ids, WebQueryRequest query, RecordExchangeService.Export options) {
        public ExportRequest {
            if (options == null) options = new RecordExchangeService.Export(null, null, null);
        }
    }

    private List<String> queryIds(CrudWeb<?, ?> web, WebQueryRequest input, List<String> selected) {
        WebQueryRequest query =
                input == null ? new WebQueryRequest(null, List.of(), List.of()) : input;
        var result = new ArrayList<String>();
        var conditions = new ArrayList<>(query.conditions());
        if (!selected.isEmpty())
            conditions.add(
                    new WebQueryCondition(
                            "id", "in", new ArrayList<Object>(new LinkedHashSet<>(selected))));
        for (int page = 1; page <= 11; page++) {
            var paged =
                    new WebQueryRequest(
                            new WebPageRequest(page, 100),
                            false,
                            conditions,
                            query.criteria(),
                            query.queryForm(),
                            query.sorts(),
                            query.uiConfigId(),
                            query.queryTemplateId(),
                            query.externalQueryValues(),
                            false,
                            query.quickSearch(),
                            query.quickSearchFields(),
                            query.navigationQueryKey(),
                            query.navigatorHostModuleAlias(),
                            query.navigatorTargetLevelKey());
            var response = action(web, PlatformAction.QUERY, null, () -> web.query(paged));
            if (response.total() > RecordExchangeService.MAX_ROWS)
                throw new IllegalArgumentException(
                        "查询超过 1000 条，请缩小范围后导出 / Narrow the export query");
            // Standard lists may return entity records or source-neutral published field
            // projections.
            for (Object record : response.records()) {
                Object id =
                        record instanceof EntityContract entity
                                ? entity.getId()
                                : record instanceof Map<?, ?> fields ? fields.get("id") : null;
                if (!(id instanceof String value) || value.isBlank())
                    throw new IllegalStateException(
                            "The list projection has no stable record identity");
                result.add(value);
            }
            if (response.records().size() < 100 || result.size() >= response.total()) break;
        }
        return result;
    }

    private Access access(CrudWeb<?, ?> web, String language) {
        if (language == null) return access(web);
        try (var ignored = LanguageContext.use(language)) {
            return access(web);
        }
    }

    private Access access(CrudWeb<?, ?> web) {
        var module = statics.find(web.webScopeName());
        boolean enabled =
                module.map(value -> value.capabilities().contains(EntityCapability.EXCHANGE))
                        .orElseGet(
                                () ->
                                        records.entityDescriptor(
                                                        web.webScopeName(),
                                                        records.mainEntityAlias(web.webScopeName()))
                                                .capabilities()
                                                .contains(EntityCapability.EXCHANGE.name()));
        if (!enabled)
            throw new PlatformAccessDeniedException("This module has not enabled record exchange");
        return new Access(
                web,
                plans.find(web.webScopeName())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Published exchange fields are unavailable")),
                module.orElse(null));
    }

    private <T> T action(CrudWeb<?, ?> web, PlatformAction operation, String id, Supplier<T> work) {
        var context =
                actions.resolveActionCode(
                        web.webScopeName(), operation.code(), id == null ? Set.of() : Set.of(id));
        if (id == null) policies.requireAuthorized(context);
        else policies.requireRecordAction(context);
        try (var ignored = ActionExecutionContextHolder.use(context)) {
            return work.get();
        }
    }

    private final class Access implements RecordExchangeAccess {
        private final CrudWeb web;
        private final ModuleExecutionPlan plan;
        private final StaticModuleDefinition staticModule;
        private final RecordExchangeDefinition definition;
        private final Map<String, Map<String, OptionBinding>> optionBindings = new HashMap<>();
        private final Map<String, Map<String, RecordExchangeReferenceSupport.Source>>
                referenceSources = new HashMap<>();
        private final Map<String, Class<?>> models = new HashMap<>();

        Access(CrudWeb<?, ?> web, ModuleExecutionPlan plan, StaticModuleDefinition staticModule) {
            this.web = web;
            this.plan = plan;
            this.staticModule = staticModule;
            var entities = new ArrayList<RecordExchangeDefinition.Entity>();
            if (staticModule != null) {
                for (var entity : staticModule.entities()) {
                    var relation =
                            plan.uiDescriptor().detailRelations().stream()
                                    .filter(
                                            value ->
                                                    entity.alias().equals(value.targetEntityAlias())
                                                            && web.webScopeName()
                                                                    .equals(
                                                                            value
                                                                                    .targetModuleAlias()))
                                    .findFirst()
                                    .orElse(null);
                    if (!entity.alias().equals(plan.readModel().mainEntityAlias())
                            && relation == null) continue;
                    var model =
                            staticModule
                                    .entityModelClasses()
                                    .getOrDefault(entity.alias(), staticModule.modelClass());
                    models.put(entity.alias(), model);
                    var ref = new HashMap<String, RecordExchangeReferenceSupport.Source>();
                    for (var rule : StaticReferenceResolver.rules(model))
                        ref.put(
                                rule.plan().sourceField(),
                                new RecordExchangeReferenceSupport.Source(
                                        web.webScopeName(),
                                        entity.alias(),
                                        rule.plan(),
                                        false,
                                        null));
                    referenceSources.put(entity.alias(), ref);
                    entities.add(
                            entity(
                                    entity.alias(),
                                    entity.name(),
                                    relation == null ? null : relation.code(),
                                    relation == null ? null : relation.parentBinding(),
                                    entity));
                }
            } else {
                var descriptor = records.describe(web.webScopeName());
                for (var entity : descriptor.entities()) {
                    var relation =
                            descriptor.relations().stream()
                                    .filter(
                                            value ->
                                                    descriptor
                                                                    .mainEntityAlias()
                                                                    .equals(
                                                                            value
                                                                                    .parentEntityAlias())
                                                            && entity.entityAlias()
                                                                    .equals(
                                                                            value
                                                                                    .childEntityAlias()))
                                    .findFirst()
                                    .orElse(null);
                    if (!entity.entityAlias().equals(descriptor.mainEntityAlias())
                            && relation == null) continue;
                    var ref = new HashMap<String, RecordExchangeReferenceSupport.Source>();
                    for (var field : entity.fields())
                        if (field.reference() != null) {
                            var reference = field.reference();
                            var referencePlan =
                                    new ReferencePlan(
                                            field.fieldName(),
                                            ReferenceTarget.of(
                                                    reference.targetModuleAlias(),
                                                    reference.targetEntityAlias()),
                                            reference.cardinality(),
                                            List.of(),
                                            reference.integrity(),
                                            reference.tenantScope(),
                                            List.of(),
                                            List.of(),
                                            reference.keyField(),
                                            reference.labelField()).withCandidateSubtitleProjection(reference.candidateSubtitleProjection());
                            ref.put(
                                    field.fieldName(),
                                    new RecordExchangeReferenceSupport.Source(
                                            web.webScopeName(),
                                            entity.entityAlias(),
                                            referencePlan,
                                            true,
                                            reference));
                        }
                    referenceSources.put(entity.entityAlias(), ref);
                    entities.add(
                            entity(
                                    entity.entityAlias(),
                                    entity.title(),
                                    relation == null ? null : relation.code(),
                                    relation == null ? null : relation.childForeignKeyField(),
                                    records.entity(
                                                                    entity.entityAlias()
                                                                                    .equals(
                                                                                            descriptor
                                                                                                    .mainEntityAlias())
                                                                            ? web.webScopeName()
                                                                            : descriptor
                                                                                    .moduleAlias(),
                                                                    entity.entityAlias())
                                                            .getDao()
                                                    instanceof DynamicRecordDao dao
                                            ? dao.getEntity()
                                            : null));
                }
            }
            entities.removeIf(entity -> !entity.main() && entity.fields().isEmpty());
            entities.sort(Comparator.comparing(entity -> !entity.main()));
            definition = new RecordExchangeDefinition(web.webScopeName(), entities);
        }

        private RecordExchangeDefinition.Entity entity(
                String alias,
                String title,
                String relation,
                String parent,
                EntityDefinition definition) {
            List<FieldDefinition> fields = definition == null ? List.of() : definition.fields();
            Set<String> matchingKeys = definition == null ? Set.of() : definition.resolvedTenantUniqueConstraints().stream()
                    .filter(constraint -> constraint.fieldNames().size() == 1)
                    .map(constraint -> constraint.fieldNames().getFirst()).collect(java.util.stream.Collectors.toSet());
            var exposed = BusinessContentReadExposure.fields(plan, relation);
            var result = new ArrayList<RecordExchangeDefinition.Field>();
            var bindings = new HashMap<String, OptionBinding>();
            var companions =
                    fields.stream()
                            .filter(
                                    field ->
                                            field.type() == FieldType.ZONED_TIMESTAMP
                                                    && exposed.contains(field.fieldName()))
                            .map(
                                    field ->
                                            FieldCompanionRules.zonedTimestampTimeZoneFieldName(
                                                    field.fieldName()))
                            .collect(java.util.stream.Collectors.toSet());
            for (var field : fields) {
                if ((!exposed.contains(field.fieldName())
                                && !companions.contains(field.fieldName()))
                        || !field.isPhysical()
                        || PlatformFieldPolicy.find(field.fieldName()) != null
                        || field.fieldName().equals(parent)
                        || field.protection().enabled()) continue;
                if (field.fieldName().startsWith("__"))
                    throw new IllegalArgumentException(
                            "Business fields cannot use reserved exchange column names");
                boolean readOnly =
                        plan.mutationFieldValidations().stream()
                                .anyMatch(
                                        rule ->
                                                Objects.equals(rule.relationAlias(), relation)
                                                        && field.fieldName()
                                                                .equals(rule.fieldName())
                                                        && rule.readOnly());
                String fieldTitle = field.name() == null ? field.fieldName() : field.name();
                fieldTitle = fieldTitle(alias, relation, field.fieldName(), fieldTitle);
                result.add(
                        new RecordExchangeDefinition.Field(
                                field.fieldName(),
                                fieldTitle,
                                field.type(),
                                (field.isRequired()
                                        || field.resolvedWriteRules().requiredOnInsert()),
                                !readOnly && !field.behavior().writeProtected(),
                                referenceSources
                                        .getOrDefault(alias, Map.of())
                                        .containsKey(field.fieldName()),
                                field.content().enabled(),
                                companions.contains(field.fieldName()),
                                matchingKeys.contains(field.fieldName())));
                if (field.optionBinding() != null)
                    bindings.put(field.fieldName(), field.optionBinding());
            }
            optionBindings.put(alias, bindings);
            return new RecordExchangeDefinition.Entity(alias, title, relation, parent, result);
        }

        private String fieldTitle(String entity, String relation, String field, String fallback) {
            var resolver = textResolvers.getIfAvailable();
            if (resolver == null) return fallback;
            String suffix =
                    "/fields/"
                            + (relation == null ? "main" : "relation/" + relation)
                            + "/"
                            + field
                            + "/label";
            var location =
                    ModuleUiTextLocations.of(plan.uiDescriptor()).stream()
                            .filter(value -> value.bindingKey().endsWith(suffix))
                            .sorted(
                                    Comparator.comparingInt(
                                            value ->
                                                    value.path().startsWith("/page/detail/editor/")
                                                            ? 0
                                                            : value.path()
                                                                            .startsWith(
                                                                                    "/defaultEditor/")
                                                                    ? 1
                                                                    : 2))
                            .findFirst();
            if (location.isPresent()) {
                var label = location.get();
                var declared =
                        label.declaredSource() != null
                                ? label.declaredSource()
                                : label.configured()
                                        ? TextReference.configured(
                                                "config/" + label.bindingKey(),
                                                label.originalText())
                                        : TextReference.code(
                                                label.bindingKey(), label.originalText());
                return resolver.resolve(
                                plan.uiDescriptor()
                                        .sourceTexts()
                                        .getOrDefault(label.bindingKey(), declared),
                                Map.of())
                        .text();
            }
            return staticModule == null
                    ? resolver.resolve(
                                    net.ximatai.muyun.spring.platform.localization
                                            .ConfigurationTextSources.metadataFieldTitle(
                                            web.webScopeName(), entity, field, fallback),
                                    Map.of())
                            .text()
                    : fallback;
        }

        @Override
        public RecordExchangeDefinition definition() {
            return definition;
        }

        @Override
        public void requireNewRowRead(String entity, String parent, Set<String> fields) {
            action(
                    web,
                    parent == null ? PlatformAction.CREATE : PlatformAction.UPDATE,
                    parent,
                    () -> {
                        if (parent != null)
                            action(web, PlatformAction.VIEW, parent, () -> web.view(parent));
                        CrudAbility<?> service = producer(definition.entity(entity));
                        if (service instanceof FieldReadAbility reader) {
                            var policy =
                                    reader.fieldReadPolicy(
                                            actions.resolveFieldReadContext(
                                                    service.getModuleAlias(), Set.of()));
                            if (policy != null
                                    && fields.stream().anyMatch(field -> !policy.allows(field)))
                                throw new PlatformAccessDeniedException(
                                        "File fields are unavailable");
                        }
                        return null;
                    });
        }

        @Override
        public String inputValue(String entity, String field, String value, String language) {
            var binding = optionBindings.getOrDefault(entity, Map.of()).get(field);
            if (binding == null) return value;
            var matches =
                    optionItems(binding).stream()
                            .filter(
                                    item ->
                                            item.code().equals(value)
                                                    || item.title().equals(value)
                                                    || optionTitle(item, language).equals(value))
                            .map(OptionItem::code)
                            .distinct()
                            .toList();
            if (matches.size() != 1)
                throw new IllegalArgumentException(
                        "字典名称未找到或重名，请填写代码 / Option name is missing or ambiguous");
            return matches.getFirst();
        }

        @Override
        public List<String> options(String entity, String field, String language) {
            var binding = optionBindings.getOrDefault(entity, Map.of()).get(field);
            return binding == null
                    ? List.of()
                    : optionItems(binding).stream()
                            .filter(OptionItem::enabled)
                            .limit(1000)
                            .map(item -> optionTitle(item, language))
                            .distinct()
                            .toList();
        }

        @Override
        public Object outputValue(String entity, String field, Object value, String language) {
            var binding = optionBindings.getOrDefault(entity, Map.of()).get(field);
            if (binding == null || value == null) return value;
            return optionItems(binding).stream()
                    .filter(item -> item.code().equals(String.valueOf(value)))
                    .map(item -> optionTitle(item, language))
                    .findFirst()
                    .orElse(String.valueOf(value));
        }

        @Override
        public Row read(String entity, String parent, String id) {
            var spec = definition.entity(entity);
            if (spec.main())
                return action(
                        web,
                        PlatformAction.VIEW,
                        id,
                        () -> row(spec, (EntityContract) web.view(id)));
            action(web, PlatformAction.VIEW, parent, () -> web.view(parent));
            return children(entity, parent, RecordExchangeService.MAX_ROWS + 1).stream()
                    .filter(child -> id.equals(child.id()))
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new PlatformAccessDeniedException(
                                            "Exchange child is unavailable"));
        }

        @Override
        public ExportRow readForExport(String entity, String parent, String id) {
            var spec = definition.entity(entity);
            if (!spec.main() || spec.fields().stream().noneMatch(RecordExchangeDefinition.Field::content))
                return RecordExchangeAccess.super.readForExport(entity, parent, id);
            return action(web, PlatformAction.VIEW, id, () -> {
                var viewed = BusinessContentWebSupport.current().viewWithSources(web, id);
                return new ExportRow(row(spec, viewed.record()), viewed.sources());
            });
        }

        @Override
        public List<Row> match(String entity, String parent, String field, Object value) {
            var spec = definition.entity(entity);
            spec.field(field);
            if (!spec.main())
                return children(entity, parent, RecordExchangeService.MAX_ROWS + 1).stream()
                        .filter(row -> Objects.equals(row.values().get(field), value))
                        .limit(2)
                        .toList();
            return action(
                    web,
                    PlatformAction.QUERY,
                    null,
                    () -> {
                        Criteria criteria =
                                Criteria.copyOf(
                                                web.queryCriteria(
                                                        new WebQueryRequest(
                                                                null, List.of(), List.of())))
                                        .eq(field, value);
                        List<? extends EntityContract> matches;
                        CrudAbility<?> service = (CrudAbility<?>) web.service();
                        if (staticModule == null)
                            matches =
                                    records.pageForAction(
                                                    web.webScopeName(),
                                                    entity,
                                                    PlatformAction.QUERY.code(),
                                                    criteria,
                                                    PageRequest.of(1, 2))
                                            .getRecords();
                        else if (service instanceof DataScopeAbility<?> scoped)
                            matches =
                                    scoped.listForAction(
                                            PlatformAction.QUERY, criteria, PageRequest.of(1, 2));
                        else matches = service.list(criteria, PageRequest.of(1, 2));
                        return matches.stream()
                                .map(record -> read(entity, null, record.getId()))
                                .toList();
                    });
        }

        @Override
        public List<Row> children(String entity, String parent, int limit) {
            var spec = definition.entity(entity);
            if (spec.main())
                throw new IllegalArgumentException("Root entity is not a detail relation");
            action(web, PlatformAction.VIEW, parent, () -> web.view(parent));
            List<? extends EntityContract> children;
            if (staticModule == null)
                children =
                        records.aggregateChildrenForView(
                                web.webScopeName(),
                                parent,
                                spec.relationCode(),
                                null,
                                PageRequest.of(1, limit));
            else if (web.service() instanceof ChildrenAbility<?> ability) {
                var relation =
                        ability.childRelations().stream()
                                .filter(item -> spec.relationCode().equals(item.relationCode()))
                                .findFirst()
                                .orElseThrow();
                children = relation.selectChildren(parent, Criteria.of(), PageRequest.of(1, limit));
            } else
                throw new IllegalArgumentException(
                        "This detail relation has no bounded exchange reader");
            if (children.size() >= RecordExchangeService.MAX_ROWS + 1)
                throw new IllegalArgumentException(
                        "Narrow the aggregate to at most 1000 file rows");
            return children.stream().map(record -> row(spec, record)).toList();
        }

        @Override
        public Row save(
                String entity,
                String parent,
                Row existing,
                Map<String, Object> changes,
                Map<String, ContentWriteContext.SourceInput> languages) {
            if (parent != null)
                throw new IllegalArgumentException(
                        "Detail changes must be submitted with their owning aggregate");
            return saveAggregate(
                            new AggregateChange(
                                    "root",
                                    entity,
                                    existing,
                                    changes,
                                    languages,
                                    existing == null ? "CREATE" : "UPDATE"),
                            List.of())
                    .get("root");
        }

        @Override
        public Map<String, Row> saveAggregate(
                AggregateChange root, List<AggregateChange> children) {
            boolean mutate =
                    !Set.of("SKIP", "CONTEXT").contains(root.operation())
                            || children.stream()
                                    .anyMatch(
                                            child ->
                                                    !Set.of("SKIP", "CONTEXT")
                                                            .contains(child.operation()));
            if (!mutate) {
                var unchanged = new LinkedHashMap<String, Row>();
                unchanged.put(root.key(), root.existing());
                children.forEach(child -> unchanged.put(child.key(), child.existing()));
                return unchanged;
            }
            var operation = root.existing() == null ? PlatformAction.CREATE : PlatformAction.UPDATE;
            return action(
                    web,
                    operation,
                    root.existing() == null ? null : root.existing().id(),
                    () -> {
                        if (root.existing() != null)
                            action(
                                    web,
                                    PlatformAction.VIEW,
                                    root.existing().id(),
                                    () -> web.view(root.existing().id()));
                        EntityContract owner = draft(root, null);
                        if (owner.getId() == null)
                            owner.setId(net.ximatai.muyun.spring.common.id.Ids.newId());
                        var submitted = new LinkedHashMap<String, EntityContract>();
                        for (var entity : definition.entities())
                            if (!entity.main()) {
                                var changes =
                                        children.stream()
                                                .filter(
                                                        child ->
                                                                child.entity()
                                                                        .equals(entity.alias()))
                                                .filter(
                                                        child ->
                                                                !Set.of("SKIP", "CONTEXT")
                                                                        .contains(
                                                                                child.operation()))
                                                .toList();
                                if (changes.isEmpty()) continue;
                                var values = new ArrayList<EntityContract>();
                                for (var change : changes) {
                                    EntityContract child = draft(change, owner.getId());
                                    if (child instanceof DynamicRecord dynamic)
                                        dynamic.putGeneratedValue(
                                                entity.parentField(), owner.getId());
                                    values.add(child);
                                    submitted.put(change.key(), child);
                                }
                                var current =
                                        root.existing() == null
                                                ? List.<Row>of()
                                                : children(
                                                        entity.alias(),
                                                        root.existing().id(),
                                                        RecordExchangeService.MAX_ROWS + 1);
                                var suppliedIds =
                                        changes.stream()
                                                .filter(child -> child.existing() != null)
                                                .map(child -> child.existing().id())
                                                .collect(java.util.stream.Collectors.toSet());
                                boolean complete =
                                        current.stream()
                                                .allMatch(
                                                        child -> suppliedIds.contains(child.id()));
                                if (owner instanceof DynamicRecord dynamic) {
                                    var dynamicValues =
                                            values.stream()
                                                    .map(child -> (DynamicRecord) child)
                                                    .toList();
                                    if (complete)
                                        dynamic.setChildren(entity.relationCode(), dynamicValues);
                                    else
                                        dynamic.setPartialChildren(
                                                entity.relationCode(), dynamicValues);
                                } else if (web.service() instanceof ChildrenAbility<?> ability) {
                                    // Static replacement follows its declared relation and current
                                    // persisted siblings.
                                    for (var child : current)
                                        if (!suppliedIds.contains(child.id()))
                                            values.add(
                                                    draft(
                                                            new AggregateChange(
                                                                    "context",
                                                                    entity.alias(),
                                                                    child,
                                                                    Map.of(),
                                                                    Map.of(),
                                                                    "CONTEXT"),
                                                            owner.getId()));
                                    var relation =
                                            ability.childRelations().stream()
                                                    .filter(
                                                            value ->
                                                                    entity.relationCode()
                                                                            .equals(
                                                                                    value
                                                                                            .relationCode()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    ((net.ximatai.muyun.spring.ability.child.ChildRelation)
                                                    relation)
                                            .assignIncomingChildren(owner, values);
                                } else
                                    throw new IllegalArgumentException(
                                            "The producer has no ordinary aggregate save contract");
                            }
                        if (staticModule == null) {
                            var validator =
                                    records.recordsForAction(
                                            web.webScopeName(),
                                            PlatformAction.IMPORT,
                                            "record-exchange");
                            for (var change : children) {
                                if (!submitted.containsKey(change.key())) continue;
                                validator.validateImport(
                                        change.entity(),
                                        (DynamicRecord) submitted.get(change.key()),
                                        change.existing() == null
                                                ? null
                                                : (DynamicRecord)
                                                        existingDraft(change, owner.getId()));
                            }
                            validator.validateImport(
                                    root.entity(),
                                    (DynamicRecord) owner,
                                    root.existing() == null
                                            ? null
                                            : records.select(
                                                    web.webScopeName(),
                                                    root.entity(),
                                                    root.existing().id()));
                        }
                        try (var source = ContentWriteContext.open(owner, root.languages())) {
                            if (root.existing() == null) web.insert(owner);
                            else web.update(root.existing().id(), owner);
                        }
                        var saved = new LinkedHashMap<String, Row>();
                        saved.put(root.key(), read(root.entity(), null, owner.getId()));
                        for (var child : children)
                            saved.put(
                                    child.key(),
                                    read(
                                            child.entity(),
                                            owner.getId(),
                                            submitted.containsKey(child.key())
                                                    ? submitted.get(child.key()).getId()
                                                    : child.existing().id()));
                        return saved;
                    });
        }

        private EntityContract existingDraft(AggregateChange change, String parentId) {
            var spec = definition.entity(change.entity());
            if (staticModule == null) {
                if (spec.main())
                    return records.select(
                            web.webScopeName(), change.entity(), change.existing().id());
                return records
                        .aggregateChildrenForView(
                                web.webScopeName(),
                                parentId,
                                spec.relationCode(),
                                change.existing().id(),
                                PageRequest.of(1, 1))
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new PlatformAccessDeniedException(
                                                "Exchange child is unavailable"));
            }
            if (spec.main()) return ((CrudAbility<?>) web.service()).select(change.existing().id());
            if (!(web.service() instanceof ChildrenAbility<?> owner))
                throw new IllegalArgumentException("This detail relation has no exchange reader");
            return owner.childRelations().stream()
                    .filter(relation -> spec.relationCode().equals(relation.relationCode()))
                    .findFirst()
                    .orElseThrow()
                    .selectChildren(
                            parentId,
                            Criteria.of().eq("id", change.existing().id()),
                            PageRequest.of(1, 1))
                    .stream()
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new PlatformAccessDeniedException(
                                            "Exchange child is unavailable"));
        }

        private EntityContract draft(AggregateChange change, String parentId) {
            var spec = definition.entity(change.entity());
            if (change.existing() != null
                    && !change.existing().values().keySet().containsAll(change.changes().keySet()))
                throw new PlatformAccessDeniedException(
                        "Exchange fields are not readable in this record scope");
            EntityContract draft;
            if (staticModule == null) {
                var dynamic =
                        change.existing() == null
                                ? records.newRecord(web.webScopeName(), change.entity())
                                : ((DynamicRecord) existingDraft(change, parentId)).copy();
                for (String relation : dynamic.getChildren().keySet())
                    dynamic.omitChildren(relation);
                change.changes().forEach(dynamic::setValue);
                if (spec.main()) dynamic.putMutationMetadata("uiConfigId", plan.formUiConfigId());
                draft = dynamic;
            } else {
                try {
                    draft =
                            change.existing() == null
                                    ? (EntityContract)
                                            models.get(change.entity())
                                                    .getDeclaredConstructor()
                                                    .newInstance()
                                    : (EntityContract)
                                            mapper.convertValue(
                                                    existingDraft(change, parentId),
                                                    models.get(change.entity()));
                    var values = new LinkedHashMap<>(change.changes());
                    var bean = new BeanWrapperImpl(draft);
                    referenceSources
                            .getOrDefault(change.entity(), Map.of())
                            .forEach(
                                    (field, source) -> {
                                        Class<?> type = bean.getPropertyType(field);
                                        if (source.plan().cardinality() == ReferenceCardinality.MANY
                                                && values.get(field) != null
                                                && type != null
                                                && (type.isArray()
                                                        || Collection.class.isAssignableFrom(type)))
                                            values.put(
                                                    field,
                                                    source.plan()
                                                            .normalizeValues(values.get(field)));
                                    });
                    mapper.updateValue(draft, values);
                } catch (ReflectiveOperationException | java.io.IOException invalid) {
                    throw new IllegalArgumentException("Invalid business cells", invalid);
                }
                if (spec.main() && web.service() instanceof ChildrenAbility<?> ability)
                    for (var relation : ability.childRelations())
                        ((net.ximatai.muyun.spring.ability.child.ChildRelation) relation)
                                .omitIncomingChildren(draft);
            }
            if (change.existing() != null) {
                draft.setId(change.existing().id());
                draft.setVersion(change.existing().version());
            }
            if (draft instanceof ContentLanguageInputs inputs)
                inputs.contentLanguageInputs(change.languages());
            return draft;
        }

        @Override
        public Reference resolve(
                String entity,
                String parent,
                String field,
                String input,
                String language,
                Map<String, Object> values) {
            return references.resolve(
                    reference(entity, field),
                    input,
                    language,
                    referenceValues(entity, parent, values));
        }

        @Override
        public String referenceLabel(
                String entity,
                String parent,
                String field,
                Object ids,
                String language,
                Map<String, Object> values) {
            return references.title(
                    reference(entity, field),
                    ids,
                    language,
                    referenceValues(entity, parent, values));
        }

        @Override
        public void requireReference(
                String entity,
                String parent,
                String field,
                Object ids,
                Map<String, Object> values) {
            references.require(
                    reference(entity, field), ids, referenceValues(entity, parent, values));
        }

        @Override
        public BusinessContentService.Sources sources(String entity, String parent, String id) {
            var spec = definition.entity(entity);
            return spec.main()
                    ? BusinessContentWebSupport.current().sources(web, id)
                    : BusinessContentWebSupport.current()
                            .childSources(web, parent, spec.relationCode(), id);
        }

        @Override
        public BusinessContentTranslationService.Reading reading(
                String entity, String parent, String id, String language) {
            return BusinessContentWebSupport.current()
                    .reading(web, location(entity, parent, id), language);
        }

        @Override
        public BusinessContentTranslationService.Maintenance translation(
                String entity,
                String parent,
                String id,
                String field,
                String language,
                BusinessContentTranslationService.Change change) {
            return BusinessContentWebSupport.current()
                    .maintain(
                            web,
                            location(entity, parent, id),
                            field,
                            language,
                            change == null ? "prepare" : "draft",
                            change);
        }

        private BusinessContentService.Location location(String entity, String parent, String id) {
            return definition.entity(entity).main()
                    ? BusinessContentService.Location.record(id)
                    : new BusinessContentService.Location(
                            parent, definition.entity(entity).relationCode(), id);
        }

        private Map<String, Object> referenceValues(
                String entity, String parent, Map<String, Object> values) {
            var result = new LinkedHashMap<String, Object>(values);
            if (!definition.entity(entity).main())
                result.put(definition.entity(entity).parentField(), parent);
            return result;
        }

        private RecordExchangeReferenceSupport.Source reference(String entity, String field) {
            var source = referenceSources.getOrDefault(entity, Map.of()).get(field);
            if (source == null)
                throw new IllegalArgumentException("Reference field is unavailable");
            return source;
        }

        private CrudAbility<?> producer(RecordExchangeDefinition.Entity entity) {
            CrudAbility<?> owner = (CrudAbility<?>) web.service();
            if (!entity.main()) {
                if (staticModule == null)
                    owner = records.entity(web.webScopeName(), entity.alias());
                else if (web.service() instanceof ChildrenAbility<?> children)
                    owner =
                            children.childRelations().stream()
                                    .filter(
                                            relation ->
                                                    entity.relationCode()
                                                            .equals(relation.relationCode()))
                                    .findFirst()
                                    .orElseThrow()
                                    .childAbility();
            }
            return owner;
        }

        private Row row(RecordExchangeDefinition.Entity entity, EntityContract record) {
            if (record == null)
                throw new PlatformAccessDeniedException("Exchange record is unavailable");
            CrudAbility<?> owner = producer(entity);
            var context =
                    actions.resolveFieldReadContext(owner.getModuleAlias(), Set.of(record.getId()));
            try (var ignored = ActionExecutionContextHolder.use(context)) {
                record =
                        WebOutputSupport.record(
                                (CrudAbility) owner, record, FieldOutputContext.EXPORT);
                var values = new LinkedHashMap<String, Object>();
                Set<String> readable = null;
                if (owner instanceof FieldReadAbility reader) {
                    var policy = reader.fieldReadPolicy(context);
                    if (policy != null && policy.restricted()) readable = policy.readableFields();
                }
                for (var field : entity.fields())
                    if (readable == null || readable.contains(field.name()))
                        values.put(field.name(), value(record, field.name()));
                return new Row(record.getId(), record.getVersion(), record.getCreatedAt(), values);
            }
        }

        private List<OptionItem> optionItems(OptionBinding binding) {
            var registry = options.getIfAvailable();
            if (registry == null)
                throw new IllegalStateException("Option source registry is unavailable");
            return registry.source(binding).options(OptionQuery.all());
        }

        private String optionTitle(OptionItem item, String language) {
            if (language == null || item.titleSource() == null) return item.title();
            var resolver = textResolvers.getIfAvailable();
            if (resolver == null) return item.title();
            try (var ignored = LanguageContext.use(language)) {
                return resolver.resolve(item.titleSource(), Map.of()).text();
            }
        }
    }

    private static Object value(EntityContract row, String field) {
        return row instanceof DynamicRecord dynamic
                ? dynamic.getValue(field)
                : new BeanWrapperImpl(row).getPropertyValue(field);
    }
}
