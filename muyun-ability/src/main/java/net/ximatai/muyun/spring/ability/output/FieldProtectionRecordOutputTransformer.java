package net.ximatai.muyun.spring.ability.output;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.List;

public class FieldProtectionRecordOutputTransformer implements RecordOutputTransformer {
    @Override
    public boolean supports(CrudAbility<?> service, RecordOutputContext context) {
        return service instanceof FieldProtectionAbility<?>;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T extends EntityContract> T transformRecord(CrudAbility<T> service,
                                                        T record,
                                                        RecordOutputContext context) {
        if (record == null || !(service instanceof FieldProtectionAbility fieldProtectionAbility)) {
            return record;
        }
        T output = (T) fieldProtectionAbility.maskProtectedFieldsForOutput(record, context.fieldContext());
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.referenceTextReadListener().onOwnerCopy(record, output);
        return output;
    }

    @Override
    public <T extends EntityContract> List<T> transformRecords(CrudAbility<T> service,
                                                               List<T> records,
                                                               RecordOutputContext context) {
        if (records == null || records.isEmpty()) {
            return records;
        }
        return records.stream()
                .map(record -> transformRecord(service, record, context))
                .toList();
    }
}
