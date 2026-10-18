package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.output.DefaultPlatformRecordOutput;
import net.ximatai.muyun.spring.ability.output.FieldProtectionRecordOutputTransformer;
import net.ximatai.muyun.spring.ability.output.OptionLoadRecordOutputTransformer;
import net.ximatai.muyun.spring.ability.output.PlatformRecordOutput;
import net.ximatai.muyun.spring.ability.output.RecordOutputContext;
import net.ximatai.muyun.spring.ability.output.RecordOutputSurface;
import net.ximatai.muyun.spring.ability.option.StaticOptionLoadPopulator;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class WebOutputSupport {
    private static final ThreadLocal<RecordOutputSurface> VIEW_SURFACE = new ThreadLocal<>();
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
        return recordOutput.page(service, page, outputContext(context));
    }

    public static <T extends EntityContract> List<T> records(CrudAbility<T> service,
                                                             List<T> records,
                                                             FieldOutputContext context) {
        return recordOutput.records(service, records, outputContext(context));
    }

    public static <T extends EntityContract> T record(CrudAbility<T> service,
                                                      T record,
                                                      FieldOutputContext context) {
        return recordOutput.record(service, record, outputContext(context));
    }

    /**
     * Reuses the normal view entry for another delivery surface without changing its field
     * protection context. Reference and other nested output surfaces keep their own contracts.
     */
    public static <R> R withViewSurface(RecordOutputSurface surface, Supplier<R> work) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(work, "work");
        RecordOutputSurface previous = VIEW_SURFACE.get();
        VIEW_SURFACE.set(surface);
        try {
            return work.get();
        } finally {
            if (previous == null) VIEW_SURFACE.remove();
            else VIEW_SURFACE.set(previous);
        }
    }

    private static RecordOutputContext outputContext(FieldOutputContext fieldContext) {
        RecordOutputContext context = RecordOutputContext.of(fieldContext);
        RecordOutputSurface surface = VIEW_SURFACE.get();
        return surface != null && context.surface() == RecordOutputSurface.VIEW
                ? new RecordOutputContext(context.fieldContext(), surface, null, null)
                : context;
    }

    private static PlatformRecordOutput defaultOutput() {
        return new DefaultPlatformRecordOutput(List.of(
                new OptionLoadRecordOutputTransformer(StaticOptionLoadPopulator.NONE),
                new net.ximatai.muyun.spring.ability.output.StandardReferenceRecordOutputTransformer(),
                new FieldProtectionRecordOutputTransformer()
        ));
    }
}
