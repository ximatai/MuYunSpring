package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.code.*;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.dynamic.capability.BusinessCodeCapabilityModule;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Configuration facts for declared Java fields and module-owned metadata fields. */
@Service
public class CodeConfigurationService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final CodeTargetCatalog statics;
    private final PlatformModuleService modules;
    private final ModuleMetadataRelationService relations;
    private final MetadataService metadata;
    private final MetadataFieldService fields;
    private final ModuleMetadataFieldService bindings;
    private final MetadataFieldDefinitionCompiler compiler;
    private final FormulaEngine formulas = new FormulaEngine();

    public CodeConfigurationService(CodeTargetCatalog statics, PlatformModuleService modules,
            ModuleMetadataRelationService relations, MetadataService metadata, MetadataFieldService fields,
            ModuleMetadataFieldService bindings, MetadataFieldDefinitionCompiler compiler) {
        this.statics = statics; this.modules = modules; this.relations = relations; this.metadata = metadata;
        this.fields = fields; this.bindings = bindings; this.compiler = compiler;
    }

    public record Source(String fieldName, String title, String type, Integer length, TextReference titleSource, String ownerTitle) {
        public Source(String fieldName, String title, String type, Integer length) {
            this(fieldName, title, type, length, PlatformFieldPolicy.titleSource(fieldName), null);
        }
        public Source {
            if (titleSource != null) title = titleSource.originalText();
        }
    }
    public record Field(String fieldName, String title, Integer length, String metadataFieldId,
                        String bindingId, Integer bindingVersion, CodeFieldRole role, CodeTrigger trigger) { }
    public record Target(String moduleAlias, String entityAlias, String title, String source,
                         String relationId, List<Field> fields, List<Source> sources) {
        public Target { fields = List.copyOf(fields); sources = List.copyOf(sources); }
        Field requireField(String name) {
            return fields.stream().filter(field -> field.fieldName().equals(name)).findFirst()
                    .orElseThrow(() -> rejection("unsupported-field", "Unsupported business code field: {value1}", name));
        }
    }
    public record BindingCommand(String moduleAlias, String entityAlias, String fieldName,
                                 CodeFieldRole role, CodeTrigger trigger, Integer expectedVersion) { }

    public List<Target> targets(String moduleAlias) {
        List<Target> result = new ArrayList<>();
        for (CodeAbility<?> service : statics.staticTargets()) {
            if (moduleAlias != null && !moduleAlias.equals(service.getModuleAlias())) continue;
            var declarations = StaticCodeFields.declarations(service.modelClass());
            var persisted = StaticCodeFields.persistedFields(service.modelClass());
            List<Field> candidates = declarations.entrySet().stream().map(entry -> {
                Column column = persisted.get(entry.getKey()).getAnnotation(Column.class);
                return new Field(entry.getKey(), column.comment().isBlank() ? entry.getKey() : column.comment(), column.length(), null, null, null,
                        entry.getValue().primary() ? CodeFieldRole.PRIMARY : CodeFieldRole.NORMAL, entry.getValue().trigger());
            }).toList();
            var protectedNames = net.ximatai.muyun.spring.ability.security.StaticFieldProtectionResolver.resolve(service.modelClass()).fields().stream()
                    .map(net.ximatai.muyun.spring.ability.security.ProtectedFieldAccessor::fieldName).collect(java.util.stream.Collectors.toSet());
            List<Source> sources = new ArrayList<>(persisted.entrySet().stream().filter(entry -> !protectedNames.contains(entry.getKey())).map(entry -> new Source(entry.getKey(), entry.getValue().getAnnotation(Column.class).comment().isBlank() ? entry.getKey() : entry.getValue().getAnnotation(Column.class).comment(),
                    entry.getValue().getType().getSimpleName(), entry.getValue().getAnnotation(Column.class).length())).toList());
            statics.parentOf(service).ifPresent(parent -> {
                var parentProtected = net.ximatai.muyun.spring.ability.security.StaticFieldProtectionResolver.resolve(parent.service().modelClass()).fields().stream()
                        .map(net.ximatai.muyun.spring.ability.security.ProtectedFieldAccessor::fieldName).collect(java.util.stream.Collectors.toSet());
                StaticCodeFields.persistedFields(parent.service().modelClass()).forEach((name, field) -> {
                    if (parentProtected.contains(name)) return;
                    Column column = field.getAnnotation(Column.class);
                    sources.add(new Source(parent.entityAlias() + "." + name, (column.comment().isBlank() ? name : column.comment()), field.getType().getSimpleName(), column.length(), PlatformFieldPolicy.titleSource(name), parent.entityAlias()));
                });
                List.copyOf(sources).stream().filter(source -> !source.fieldName().contains(".")).forEach(source -> sources.add(
                        new Source(service.codeEntityAlias() + "." + source.fieldName(), source.title(), source.type(), source.length(), source.titleSource(), null)));
            });
            var module = modules.resolveVisibleModule(service.getModuleAlias());
            var table = service.modelClass().getAnnotation(net.ximatai.muyun.database.core.annotation.Table.class);
            String title = module != null && module.getTitle() != null && !module.getTitle().isBlank() ? module.getTitle()
                    : table != null && !table.comment().isBlank() ? table.comment() : service.codeEntityAlias();
            result.add(new Target(service.getModuleAlias(), service.codeEntityAlias(), title, "STATIC", null, candidates, sources));
        }
        List<PlatformModule> visible = moduleAlias == null ? modules.listVisibleModules()
                : Optional.ofNullable(modules.resolveVisibleModule(moduleAlias)).stream().toList();
        for (PlatformModule module : visible) {
            if (module.getModuleKind() != ModuleKind.DYNAMIC) continue;
            try (var scope = net.ximatai.muyun.spring.common.tenant.TenantContext.use(module.getTenantId())) {
                for (var relation : relations.list(Criteria.of().eq("moduleAlias", module.getAlias()), ALL)) {
                    Metadata schema = metadata.select(relation.getMetadataId());
                    if (schema != null) result.add(dynamicTarget(module.getAlias(), relation, schema));
                }
            }
        }
        return result.stream().sorted(Comparator.comparing(Target::moduleAlias).thenComparing(Target::entityAlias)).toList();
    }

    private Target dynamicTarget(String module, ModuleMetadataRelation relation, Metadata schema) {
        List<Field> candidates = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        for (MetadataField field : fields.list(Criteria.of().eq("metadataId", schema.getId()), ALL)) {
            if (MetadataSystemFieldCatalog.isRuntimeReserved(field) && !businessContextField(schema, field)) continue;
            var binding = bindings.findByRelationAndField(relation.getId(), field.getId());
            FieldDefinition definition = compiler.compile(field, relation.getId(), binding);
            if (definition.isPhysical() && !definition.protection().enabled())
                sources.add(new Source(field.getFieldName(), field.getTitle(), definition.type().name(), definition.length()));
            if (BusinessCodeCapabilityModule.isEligible(definition) && !Boolean.TRUE.equals(field.getSystemManaged())
                    && field.getOwnerFieldId() == null
                    && (binding == null || binding.getReferenceModuleAlias() == null || binding.getReferenceModuleAlias().isBlank())
                    && !field.getFieldName().equals(relation.getForeignKey()))
                candidates.add(new Field(field.getFieldName(), field.getTitle(), definition.length(), field.getId(),
                        binding == null ? null : binding.getId(), binding == null ? null : binding.getVersion(),
                        binding == null ? null : binding.getBusinessCodeRole(), binding == null ? null : binding.getBusinessCodeTrigger()));
        }
        if (relation.getParentMetadataId() != null) {
            Metadata parent = metadata.select(relation.getParentMetadataId());
            if (parent != null) for (MetadataField field : fields.list(Criteria.of().eq("metadataId", parent.getId()), ALL)) {
                if (MetadataSystemFieldCatalog.isRuntimeReserved(field) && !businessContextField(parent, field)) continue;
                var definition = compiler.compile(field);
                if (!definition.isPhysical() || definition.protection().enabled()) continue;
                sources.add(new Source(parent.getAlias() + "." + field.getFieldName(), field.getTitle(), definition.type().name(), definition.length(), PlatformFieldPolicy.titleSource(field.getFieldName()), parent.getTitle()));
            }
            List<Source> own = List.copyOf(sources);
            own.stream().filter(source -> !source.fieldName().contains(".")).forEach(source -> sources.add(
                    new Source(schema.getAlias() + "." + source.fieldName(), source.title(), source.type(), source.length(), source.titleSource(), null)));
        }
        return new Target(module, schema.getAlias(), schema.getTitle(), "DYNAMIC", relation.getId(), candidates, sources);
    }

    private boolean businessContextField(Metadata schema, MetadataField field) {
        return Boolean.TRUE.equals(schema.getDataScopeEnabled()) && net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields.dataScopeFields()
                .stream().anyMatch(definition -> definition.fieldName().equals(field.getFieldName()));
    }

    public Target requireTarget(String module, String entity) {
        List<Target> matches = targets(module).stream().filter(target -> entity.equals(target.entityAlias())).toList();
        if (matches.size() != 1) throw rejection("unique-target", "Business code target must resolve uniquely: {value1}/{value2}", module, entity);
        return matches.getFirst();
    }

    @Transactional
    public Target bind(BindingCommand command) {
        Target target = requireTarget(command.moduleAlias(), command.entityAlias());
        if (!target.source().equals("DYNAMIC")) throw rejection("static-binding", "Static code bindings are declared by the Java model");
        Field field = target.requireField(command.fieldName());
        var module = modules.resolveVisibleModule(target.moduleAlias());
        try (var scope = net.ximatai.muyun.spring.common.tenant.TenantContext.use(module.getTenantId())) {
            var binding = bindings.findByRelationAndField(target.relationId(), field.metadataFieldId());
            if (binding == null) {
                if (command.expectedVersion() != null) throw rejection("binding-changed", "Business code binding changed; refresh before saving");
                binding = new ModuleMetadataField(); binding.setTitle(field.title()); binding.setRelationId(target.relationId()); binding.setMetadataFieldId(field.metadataFieldId());
            } else if (!Objects.equals(command.expectedVersion(), binding.getVersion())) {
                throw rejection("binding-changed", "Business code binding changed; refresh before saving");
            }
            binding.setBusinessCodeRole(command.role()); binding.setBusinessCodeTrigger(command.trigger());
            if (binding.getId() == null) bindings.insert(binding); else bindings.update(binding);
        }
        return requireTarget(target.moduleAlias(), target.entityAlias());
    }

    public Field validateTarget(CodeRule rule) {
        Target target = requireTarget(rule.getModuleAlias(), rule.getEntityAlias());
        Field field = target.fields().stream().filter(candidate -> Objects.equals(candidate.fieldName(), rule.getFieldName())
                || rule.getMetadataFieldId() != null && rule.getMetadataFieldId().equals(candidate.metadataFieldId())
                || rule.getModuleMetadataFieldId() != null && rule.getModuleMetadataFieldId().equals(candidate.bindingId()))
                .findFirst().orElseThrow(() -> rejection("unknown-target", "Unknown business code target field"));
        if (field.role() == null) throw rejection("binding-required", "Declare the business code field before configuring a rule");
        if (rule.getFieldName() != null && !rule.getFieldName().equals(field.fieldName())
                || rule.getMetadataFieldId() != null && !rule.getMetadataFieldId().equals(field.metadataFieldId())
                || rule.getModuleMetadataFieldId() != null && !rule.getModuleMetadataFieldId().equals(field.bindingId()))
            throw rejection("identifier-mismatch", "Business code target identifiers disagree");
        rule.setFieldName(field.fieldName()); rule.setFieldRole(field.role());
        rule.setMetadataFieldId(field.metadataFieldId()); rule.setModuleMetadataFieldId(field.bindingId());
        return field;
    }

    public void validateRule(CodeRule rule, List<CodeRuleSegment> segments) {
        Field field = validateTarget(rule);
        Target target = requireTarget(rule.getModuleAlias(), rule.getEntityAlias());
        Set<String> available = new HashSet<>(); target.sources().forEach(source -> available.add(source.fieldName()));
        if (rule.getMode() == CodeMode.MANUAL) return;
        long maximumLength = 0;
        for (CodeRuleSegment segment : segments) {
            if (segment.getSegmentType() == CodeSegmentType.FORMULA) {
                formulas.parse("code", segment.getFormulaExpr());
                if (formulas.containsAssignment(segment.getFormulaExpr())) throw rejection("formula-assignment", "Code formulas must return a value without assignments");
                for (String source : formulas.referencedFields(segment.getFormulaExpr())) requireSource(available, source, field.fieldName());
            } else if (Set.of(CodeSegmentType.FIELD_VALUE, CodeSegmentType.VALUE_MAPPING).contains(segment.getSegmentType())
                    || segment.getSegmentType() == CodeSegmentType.CONTEXT_VAR && field.trigger() != CodeTrigger.EXPLICIT) {
                requireSource(available, segment.getSourceRef(), field.fieldName());
            }
            maximumLength += maximumSegmentLength(segment, rule, target);
            if (segment.getSeparator() != null) maximumLength += segment.getSeparator().length();
        }
        int limit = field.length() != null && field.length() > 0 ? Math.min(field.length(), CodeFieldDefinition.MAX_CODE_LENGTH) : CodeFieldDefinition.MAX_CODE_LENGTH;
        if (maximumLength > limit) throw rejection("format-length", "Code format exceeds the target field length: {value1}", limit);
    }
    private int maximumSegmentLength(CodeRuleSegment segment, CodeRule rule, Target target) {
        Integer bound = switch (segment.getSegmentType()) {
            case CONSTANT -> segment.getFixedValue().length();
            case SYSTEM_TIME -> (segment.getDateFormat() == null ? CodeDateFormat.YYYYMMDD : segment.getDateFormat()).pattern().length();
            case SEQUENCE -> Math.max(segment.getLength() != null ? segment.getLength() : rule.getSequencePolicy().getSequenceLength() == null ? 1 : rule.getSequencePolicy().getSequenceLength(),
                    Long.toString(rule.getSequencePolicy().getMaxValue() == null ? Long.MAX_VALUE : rule.getSequencePolicy().getMaxValue()).length());
            case VALUE_MAPPING -> segment.getMappings().stream().map(CodeValueMapping::getTargetValue).filter(Objects::nonNull).mapToInt(String::length).max().orElse(0);
            case FIELD_VALUE, CONTEXT_VAR -> target.sources().stream().filter(source -> source.fieldName().equals(segment.getSourceRef())).findFirst().map(this::sourceLength).orElse(null);
            case FORMULA -> null;
        };
        if (bound != null && segment.getNullPolicy() == CodeNullPolicy.USE_DEFAULT && segment.getFixedValue() != null
                && Set.of(CodeSegmentType.FIELD_VALUE, CodeSegmentType.CONTEXT_VAR, CodeSegmentType.FORMULA).contains(segment.getSegmentType()))
            bound = Math.max(bound, segment.getFixedValue().length());
        if (segment.getLength() != null && segment.getLength() > 0 && segment.getTruncatePolicy() != null && segment.getTruncatePolicy() != CodeTruncatePolicy.NONE)
            bound = bound == null ? segment.getLength() : Math.min(bound, segment.getLength());
        if (bound == null) throw rejection("unbounded-segment", "Declare a length and truncation policy for an unbounded code segment");
        if (segment.getPadChar() != null && segment.getPadChar().length() > 1) throw rejection("padding-character", "Code padding requires a single character");
        if (segment.getLength() != null && segment.getPadMode() != null && segment.getPadMode() != CodePadMode.NONE) bound = Math.max(bound, segment.getLength());
        return bound;
    }
    private Integer sourceLength(Source source) {
        if (source.length() != null && source.length() > 0) return source.length();
        return switch (source.type().toUpperCase(java.util.Locale.ROOT)) {
            case "INTEGER", "INT" -> 11;
            case "LONG" -> 20;
            case "BOOLEAN" -> 5;
            case "DATE", "LOCALDATE" -> 10;
            case "DATETIME", "LOCALDATETIME", "INSTANT" -> 35;
            default -> null;
        };
    }
    private void requireSource(Set<String> available, String source, String target) {
        if (!available.contains(source)) throw rejection("unavailable-source", "Unknown or unavailable code source: {value1}", source);
        if (target.equals(source)) throw rejection("self-dependency", "A code field cannot depend on itself");
    }

    static PlatformException rejection(String key, String fallback, Object... values) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) arguments.put("value" + (i + 1), String.valueOf(values[i]));
        return net.ximatai.muyun.spring.common.exception.PlatformErrors.badRequestText(
                net.ximatai.muyun.spring.common.i18n.TextReference.code("platform.rejection.code-configuration." + key, fallback), arguments);
    }

    public String normalizeValue(CodeRule rule, String value) {
        Target target = requireTarget(rule.getModuleAlias(), rule.getEntityAlias());
        Field field = target.requireField(rule.getFieldName());
        String normalized;
        var service = statics.find(rule.getModuleAlias(), rule.getEntityAlias());
        if (service.isPresent()) normalized = StaticCodeFields.normalizeCode(service.get().modelClass(), field.fieldName(), value);
        else {
            var module = modules.resolveVisibleModule(target.moduleAlias());
            try (var scope = net.ximatai.muyun.spring.common.tenant.TenantContext.use(module.getTenantId())) {
                var definition = compiler.compile(fields.select(field.metadataFieldId()), target.relationId(), bindings.findByRelationAndField(target.relationId(), field.metadataFieldId()));
                normalized = (String) definition.behavior().writeRules().normalize(value);
            }
        }
        int limit = field.length() != null && field.length() > 0 ? Math.min(field.length(), CodeFieldDefinition.MAX_CODE_LENGTH) : CodeFieldDefinition.MAX_CODE_LENGTH;
        if (normalized != null && normalized.length() > limit) throw rejection("generated-length", "Generated code exceeds the target field length: {value1}", limit);
        return normalized;
    }
}
