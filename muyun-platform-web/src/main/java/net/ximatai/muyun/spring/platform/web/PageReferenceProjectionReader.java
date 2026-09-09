package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadReader;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Batches page-only dotted projections through the normal reference read contract. */
public final class PageReferenceProjectionReader {
    private PageReferenceProjectionReader() {
    }

    public static <T> void populate(ReferenceTarget rootTarget, List<T> records, List<String> fields,
                                    Function<T, Map<String, Object>> values,
                                    BiConsumer<T, Map<String, Object>> output) {
        if (records == null || records.isEmpty() || fields == null || fields.stream().noneMatch(field -> field.contains("."))) {
            return;
        }
        List<PageReferencePath> paths = fields.stream().filter(field -> field != null && field.contains("."))
                .distinct().map(field -> PageReferencePathCompiler.compile(rootTarget, field)).toList();
        for (PageReferencePath path : paths) {
            ReferencePlan source = PageReferencePathCompiler.plan(rootTarget, path.loadPath().sourceField());
            List<List<String>> idsByRecord = new java.util.ArrayList<>();
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (T record : records) {
                List<String> valuesForRecord = source.normalizeValues(values.apply(record).get(source.sourceField()));
                idsByRecord.add(valuesForRecord);
                ids.addAll(valuesForRecord);
            }
            Map<String, Object> loaded = ReferenceLoadReader.readAll(path.loadPath(), List.copyOf(ids),
                    PageReferenceProjectionReader::ability, PlatformAbilityRuntime.referenceReadObserver());
            for (int index = 0; index < records.size(); index++) {
                List<String> recordIds = idsByRecord.get(index);
                Object value = recordIds.isEmpty() ? null : loaded.get(recordIds.getFirst());
                Map<String, Object> projection = new LinkedHashMap<>();
                projection.put(path.fieldName(), value);
                output.accept(records.get(index), projection);
            }
        }
    }

    private static ReferenceAbility<?> ability(ReferenceTarget target) {
        return PlatformAbilityRuntime.referenceTargetResolver().resolve(target).orElseThrow(() ->
                new PlatformException("reference target is not registered: " + target.qualifiedName()));
    }
}
