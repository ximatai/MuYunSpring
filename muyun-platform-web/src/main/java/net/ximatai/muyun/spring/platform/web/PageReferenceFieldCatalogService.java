package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.FormulaReferencePathCompiler;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionFieldDefinition;
import net.ximatai.muyun.spring.common.option.OptionFieldResolver;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Directory for management-page reference paths. It reads declarations only, never business records. */
@Service
public class PageReferenceFieldCatalogService {
    private final DynamicRecordService dynamicRecordService;
    private final StaticModuleDefinitionCatalog staticModules;
    private final FieldUiControlPropertyService fieldUiControlProperties;

    @Autowired
    public PageReferenceFieldCatalogService(DynamicRecordService dynamicRecordService,
                                            StaticModuleDefinitionCatalog staticModules,
                                            ObjectProvider<FieldUiControlPropertyService> fieldUiControlProperties) {
        this(dynamicRecordService, staticModules,
                fieldUiControlProperties == null ? null : fieldUiControlProperties.getIfAvailable());
    }

    public PageReferenceFieldCatalogService(DynamicRecordService dynamicRecordService,
                                            StaticModuleDefinitionCatalog staticModules) {
        this(dynamicRecordService, staticModules, (FieldUiControlPropertyService) null);
    }

    PageReferenceFieldCatalogService(DynamicRecordService dynamicRecordService,
                                     StaticModuleDefinitionCatalog staticModules,
                                     FieldUiControlPropertyService fieldUiControlProperties) {
        this.dynamicRecordService = dynamicRecordService;
        this.staticModules = staticModules;
        this.fieldUiControlProperties = fieldUiControlProperties;
    }

    public PageReferenceFieldCatalog list(String moduleAlias, String path) {
        String normalizedPath = path == null || path.isBlank() ? null : path.trim();
        ReferenceTarget target = normalizedPath == null ? rootTarget(moduleAlias)
                : targetAt(moduleAlias, normalizedPath);
        String prefix = normalizedPath == null ? "" : normalizedPath + ".";
        int hopDepth = normalizedPath == null ? 0 : normalizedPath.split("\\.", -1).length;
        // The threshold is a root-composer capability. Nested reference-directory reads must stay
        // source-only and must not repeat an unrelated metadata query for every tree expansion.
        Integer radioMaxOptions = normalizedPath == null ? dictionaryRadioMaxOptions() : null;
        return new PageReferenceFieldCatalog(moduleAlias, normalizedPath, radioMaxOptions, fields(target).stream()
                .map(field -> descriptor(moduleAlias, prefix + field.name(), field, normalizedPath != null, hopDepth)).toList());
    }

    /** Validates a dotted terminal field against the same protected directory used by composition. */
    public String title(String moduleAlias, String dottedPath) {
        int separator = dottedPath.lastIndexOf('.');
        if (separator < 1) throw new IllegalArgumentException("page reference field must be dotted: " + dottedPath);
        String parent = dottedPath.substring(0, separator);
        String terminal = dottedPath.substring(separator + 1);
        return fields(targetAt(moduleAlias, parent)).stream().filter(field -> terminal.equals(field.name()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "page reference terminal field does not exist or is protected: " + dottedPath)).label();
    }

    private ReferenceTarget targetAt(String moduleAlias, String path) {
        String[] segments = path.split("\\.", -1);
        if (segments.length > PageReferencePathCompiler.MAX_HOPS) {
            throw new IllegalArgumentException("page reference path exceeds " + PageReferencePathCompiler.MAX_HOPS
                    + " reference hops: " + path);
        }
        ReferenceTarget current = rootTarget(moduleAlias);
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("page reference path contains a blank segment: " + path);
            }
            if (fields(current).stream().noneMatch(field -> segment.equals(field.name()))) {
                throw new IllegalArgumentException("page reference field does not exist or is protected: " + path);
            }
            ReferencePlan plan = PageReferencePathCompiler.plan(current, segment);
            if (plan.cardinality() != ReferenceCardinality.ONE) {
                throw new IllegalArgumentException("page reference path requires ONE cardinality: " + path);
            }
            current = plan.target();
        }
        return current;
    }

    private List<DirectoryField> fields(ReferenceTarget target) {
        String targetModuleAlias = target.qualifiedName();
        StaticModuleDefinition staticDefinition = staticModules.find(targetModuleAlias).orElse(null);
        if (staticDefinition != null) {
            EntityDefinition entity = staticDefinition.entities().stream()
                    .filter(candidate -> target.entityAlias().equals(candidate.alias())).findFirst()
                    .orElseGet(() -> staticDefinition.entities().isEmpty() ? null : staticDefinition.entities().getFirst());
            if (entity == null) throw new IllegalArgumentException("static target has no main entity: " + targetModuleAlias);
            Map<String, OptionFieldDefinition> options = staticDefinition.modelClass() == null ? Map.of()
                    : OptionFieldResolver.resolve(staticDefinition.modelClass()).stream()
                    .collect(Collectors.toMap(OptionFieldDefinition::fieldName, Function.identity(), (left, ignored) -> left));
            return entity.fields().stream().filter(field -> !field.protection().enabled())
                    .filter(field -> composable(field.fieldName()))
                    .map(field -> new DirectoryField(field.fieldName(), field.name(), FieldValueType.from(field.type()),
                            reference(target, field.fieldName()), false,
                            options.get(field.fieldName()) == null ? null : options.get(field.fieldName()).binding(),
                            options.get(field.fieldName()) == null ? null : options.get(field.fieldName()).selectionMode())).toList();
        }
        DynamicModuleDescriptor dynamic = dynamic(target.moduleAlias());
        if (dynamic != null) {
            DynamicModuleDescriptor descriptor = dynamic;
            DynamicEntityDescriptor entity = descriptor.entities().stream()
                    .filter(item -> target.entityAlias().equals(item.entityAlias())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("dynamic target entity is unavailable: " + targetModuleAlias));
            return entity.fields().stream()
                    .filter(field -> !field.encrypted() && !field.signed()
                            && (field.maskingPolicy() == null || field.maskingPolicy().isBlank()))
                    .filter(field -> composable(field.fieldName()))
                    .map(field -> new DirectoryField(field.fieldName(), field.title(), FieldValueType.from(field.type()),
                            reference(target, field.fieldName()), field.writeProtected(), field.optionBinding(),
                            field.selectionMode()))
                    .toList();
        }
        throw new IllegalArgumentException("reference target module is unavailable: " + targetModuleAlias);
    }

    /** Dynamic module aliases and their main entity aliases are independent identities. */
    ReferenceTarget rootTarget(String moduleAlias) {
        DynamicModuleDescriptor dynamic = dynamic(moduleAlias);
        return dynamic == null ? ReferenceTargets.fromModuleAlias(moduleAlias)
                : ReferenceTarget.of(dynamic.moduleAlias(), dynamic.mainEntityAlias());
    }

    private DynamicModuleDescriptor dynamic(String moduleAlias) {
        try {
            return dynamicRecordService.describe(moduleAlias);
        } catch (IllegalArgumentException | ModuleDefinitionException ignored) {
            return null;
        }
    }

    private static ReferencePlan reference(ReferenceTarget target, String field) {
        try {
            return PageReferencePathCompiler.plan(target, field);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean composable(String fieldName) {
        PlatformFieldPolicy policy = PlatformFieldPolicy.find(fieldName);
        return policy == null || policy.composable();
    }

    /**
     * Page composition must apply the same radio threshold as published form descriptors.
     * This is a composer capability, not a dictionary-source fact, so it is supplied once with
     * the directory response rather than copied to every field.
     */
    private int dictionaryRadioMaxOptions() {
        if (fieldUiControlProperties == null) return PageReferenceFieldCatalog.DEFAULT_DICTIONARY_RADIO_MAX_OPTIONS;
        return fieldUiControlProperties.listByFieldUiControlAliases(List.of("dictionary_radio")).stream()
                .filter(property -> "dictionary_radio".equals(property.getFieldUiControlAlias()))
                .filter(property -> "maxOptions".equals(property.getAttributeAlias()))
                .map(FieldUiControlProperty::getDefaultValue)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .mapToInt(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        return PageReferenceFieldCatalog.DEFAULT_DICTIONARY_RADIO_MAX_OPTIONS;
                    }
                })
                .filter(value -> value > 0)
                .findFirst()
                .orElse(PageReferenceFieldCatalog.DEFAULT_DICTIONARY_RADIO_MAX_OPTIONS);
    }

    private PageReferenceFieldCatalog.Field descriptor(String moduleAlias, String name, DirectoryField field, boolean nested,
                                                       int hopDepth) {
        ReferencePlan reference = field.reference();
        FormulaEligibility formula = formulaEligibility(moduleAlias, name, nested, field);
        return new PageReferenceFieldCatalog.Field(name, name, field.label(), field.type(),
                reference == null ? null : referenceModuleAlias(reference.target()),
                reference == null ? null : reference.cardinality().name(),
                reference != null && reference.cardinality() == ReferenceCardinality.ONE
                        && hopDepth < PageReferencePathCompiler.MAX_HOPS,
                nested || field.readOnly(), PlatformFieldPolicy.find(field.name()) != null,
                formula.readable(), formula.disabledReason(),
                field.optionBinding() == null ? null : field.optionBinding().sourceType(),
                field.optionBinding() == null ? null
                        : (field.selectionMode() == null ? OptionSelectionMode.SINGLE : field.selectionMode()).name());
    }

    private FormulaEligibility formulaEligibility(String moduleAlias, String path, boolean nested, DirectoryField field) {
        if (!nested) {
            if (PlatformFieldPolicy.find(field.name()) != null)
                return new FormulaEligibility(false, "系统管理字段不能用于业务规则");
            if (field.readOnly()) return new FormulaEligibility(false, "当前字段不属于可配置业务字段");
            if (field.reference() != null && field.reference().cardinality() == ReferenceCardinality.MANY)
                return new FormulaEligibility(false, "集合引用不能作为标量公式字段");
            if (field.type() == FieldValueType.JSON)
                return new FormulaEligibility(false, "对象字段不能作为标量公式字段");
            return new FormulaEligibility(true, null);
        }
        try {
            FormulaReferencePathCompiler.compile(rootTarget(moduleAlias), path,
                    PlatformAbilityRuntime.referenceTargetResolver());
            return new FormulaEligibility(true, null);
        } catch (IllegalArgumentException exception) {
            return new FormulaEligibility(false, exception.getMessage());
        }
    }

    private String referenceModuleAlias(ReferenceTarget target) {
        DynamicModuleDescriptor dynamic = dynamic(target.moduleAlias());
        return dynamic == null ? target.qualifiedName() : dynamic.moduleAlias();
    }

    private record DirectoryField(String name, String label, FieldValueType type, ReferencePlan reference,
                                  boolean readOnly, OptionBinding optionBinding, OptionSelectionMode selectionMode) {
    }

    private record FormulaEligibility(boolean readable, String disabledReason) {
    }
}
