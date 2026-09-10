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
import org.springframework.stereotype.Service;

import java.util.List;

/** Directory for management-page reference paths. It reads declarations only, never business records. */
@Service
public class PageReferenceFieldCatalogService {
    private final DynamicRecordService dynamicRecordService;
    private final StaticModuleDefinitionCatalog staticModules;

    public PageReferenceFieldCatalogService(DynamicRecordService dynamicRecordService,
                                            StaticModuleDefinitionCatalog staticModules) {
        this.dynamicRecordService = dynamicRecordService;
        this.staticModules = staticModules;
    }

    public PageReferenceFieldCatalog list(String moduleAlias, String path) {
        String normalizedPath = path == null || path.isBlank() ? null : path.trim();
        ReferenceTarget target = normalizedPath == null ? rootTarget(moduleAlias)
                : targetAt(moduleAlias, normalizedPath);
        String prefix = normalizedPath == null ? "" : normalizedPath + ".";
        int hopDepth = normalizedPath == null ? 0 : normalizedPath.split("\\.", -1).length;
        return new PageReferenceFieldCatalog(moduleAlias, normalizedPath, fields(target).stream()
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
            return entity.fields().stream().filter(field -> !field.protection().enabled())
                    .filter(field -> composable(field.fieldName()))
                    .map(field -> new DirectoryField(field.fieldName(), field.name(), FieldValueType.from(field.type()),
                            reference(target, field.fieldName()), false)).toList();
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
                            reference(target, field.fieldName()), field.writeProtected()))
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
                formula.readable(), formula.disabledReason());
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
                                  boolean readOnly) {
    }

    private record FormulaEligibility(boolean readable, String disabledReason) {
    }
}
