package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.util.HashSet;
import java.util.Set;

/** Read enrichments may round-trip through shared forms but are never mutation input. */
final class DynamicWebRecordReadFields {
    private DynamicWebRecordReadFields() {
    }

    static Set<String> readOnlyOutputs(DynamicRecordService service, String moduleAlias, DynamicRecord record) {
        Set<String> outputs = new HashSet<>(Set.of(
                StandardEntitySchema.DELETED_FIELD, StandardEntitySchema.DELETED_AT_FIELD,
                StandardEntitySchema.DELETED_BY_FIELD, StandardEntitySchema.CREATED_AT_FIELD,
                StandardEntitySchema.CREATED_BY_FIELD, StandardEntitySchema.UPDATED_AT_FIELD,
                StandardEntitySchema.UPDATED_BY_FIELD));
        service.references(moduleAlias, record.getEntity().alias()).forEach(reference ->
                reference.projections().forEach(projection -> outputs.add(projection.outputField())));
        // A declared field remains subject to normal type and write validation, even when a
        // reference also projects to it. Never silently discard legitimate business input.
        record.getEntity().fields().forEach(field -> outputs.remove(field.fieldName()));
        return outputs;
    }
}
