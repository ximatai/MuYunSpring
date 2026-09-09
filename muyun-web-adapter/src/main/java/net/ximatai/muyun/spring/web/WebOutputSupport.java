package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.output.DefaultPlatformRecordOutput;
import net.ximatai.muyun.spring.ability.output.FieldProtectionRecordOutputTransformer;
import net.ximatai.muyun.spring.ability.output.OptionLoadRecordOutputTransformer;
import net.ximatai.muyun.spring.ability.output.PlatformRecordOutput;
import net.ximatai.muyun.spring.ability.option.StaticOptionLoadPopulator;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;

import java.util.List;

public final class WebOutputSupport {
    private static volatile PlatformRecordOutput recordOutput = defaultOutput();

    private WebOutputSupport() {
    }

    public static void configure(PlatformRecordOutput output) {
        recordOutput = output == null ? defaultOutput() : output;
    }

    public static void reset() {
        recordOutput = defaultOutput();
    }

    public static <T extends EntityContract> PageResult<T> page(CrudAbility<T> service,
                                                                PageResult<T> page,
                                                                FieldOutputContext context) {
        return recordOutput.page(service, page, context);
    }

    public static <T extends EntityContract> List<T> records(CrudAbility<T> service,
                                                             List<T> records,
                                                             FieldOutputContext context) {
        return recordOutput.records(service, records, context);
    }

    public static <T extends EntityContract> T record(CrudAbility<T> service,
                                                      T record,
                                                      FieldOutputContext context) {
        return recordOutput.record(service, record, context);
    }

    private static PlatformRecordOutput defaultOutput() {
        return new DefaultPlatformRecordOutput(List.of(
                new net.ximatai.muyun.spring.ability.output.AuditReferenceRecordOutputTransformer(),
                new OptionLoadRecordOutputTransformer(StaticOptionLoadPopulator.NONE),
                new FieldProtectionRecordOutputTransformer()
        ));
    }
}
