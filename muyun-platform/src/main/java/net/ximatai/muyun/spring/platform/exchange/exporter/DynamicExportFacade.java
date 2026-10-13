package net.ximatai.muyun.spring.platform.exchange.exporter;

import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPresentation;

import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.template.DynamicExchangeTemplatePlanBuilder;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class DynamicExportFacade {
    private final DynamicExportExecutor executor;
    private final ExcelWorkbookPlanWriter workbookWriter;
    private ExcelWorkbookPresentation presentation;

    public DynamicExportFacade(DynamicRecordService recordService, OptionSourceRegistry optionSourceRegistry) {
        this(new DynamicExportExecutor(recordService, new DynamicExchangeTemplatePlanBuilder(optionSourceRegistry)),
                new ExcelWorkbookPlanWriter());
    }

    @Autowired
    public DynamicExportFacade(DynamicRecordService records, OptionSourceRegistry options,
            ExcelWorkbookPresentation presentation) {
        this(records, options);
        this.presentation = presentation;
    }

    DynamicExportFacade(DynamicExportExecutor executor, ExcelWorkbookPlanWriter workbookWriter) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.workbookWriter = Objects.requireNonNull(workbookWriter, "workbookWriter must not be null");
    }

    public byte[] exportWorkbook(DynamicExportCommand command) {
        ExcelWorkbookPlan plan = executor.export(command);
        if (presentation != null) plan = presentation.project(plan, command.descriptor());
        return workbookWriter.writeToBytes(plan);
    }
}
