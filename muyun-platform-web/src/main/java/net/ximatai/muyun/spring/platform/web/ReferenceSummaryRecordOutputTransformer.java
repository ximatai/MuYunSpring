package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.output.RecordOutputContext;
import net.ximatai.muyun.spring.ability.output.RecordOutputSurface;
import net.ximatai.muyun.spring.ability.output.RecordOutputTransformer;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies the existing list-summary reader to typed detail/form output before text capture and masking. */
@Component
@Order(20)
public final class ReferenceSummaryRecordOutputTransformer implements RecordOutputTransformer {
    @Override
    public boolean supports(CrudAbility<?> service, RecordOutputContext context) {
        return service != null && service.modelClass() != null
                && (context.surface() == RecordOutputSurface.VIEW || context.surface() == RecordOutputSurface.FORM)
                && !StaticReferenceResolver.summaryPlans(service.modelClass()).isEmpty();
    }

    @Override
    public <T extends EntityContract> T transformRecord(CrudAbility<T> service, T record, RecordOutputContext context) {
        if (record != null) transformRecords(service, List.of(record), context);
        return record;
    }

    @Override
    public <T extends EntityContract> List<T> transformRecords(CrudAbility<T> service, List<T> records,
                                                              RecordOutputContext context) {
        if (records == null || records.isEmpty() || !supports(service, context)) return records;
        var summaries = StaticReferenceResolver.summaryPlans(service.modelClass());
        List<String> outputs = summaries.stream().map(summary -> summary.outputField()).toList();
        List<Map<String, Object>> sources = records.stream().map(record -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", record.getId());
            net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.referenceTextReadListener().onOwnerCopy(record, values);
            for (var summary : summaries) {
                Object value = StaticReferenceResolver.readLoadedValue(record, summary.sourceField());
                if (service instanceof FieldProtectionAbility<?> protection
                        && !Objects.deepEquals(value, protection.maskProtectedValue(
                                summary.sourceField(), value, context.fieldContext()))) {
                    value = null;
                }
                values.put(summary.sourceField(), value);
            }
            return values;
        }).toList();
        var projected = ReferenceReadProjectionPostProcessor.apply(service.modelClass(), sources, outputs);
        for (int index = 0; index < records.size(); index++) {
            for (String output : outputs) {
                StaticReferenceResolver.writeLoadedValue(records.get(index), output, projected.get(index).get(output));
            }
        }
        return records;
    }
}
