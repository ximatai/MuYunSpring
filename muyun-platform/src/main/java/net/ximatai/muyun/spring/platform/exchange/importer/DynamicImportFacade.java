package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;
import net.ximatai.muyun.spring.platform.exchange.reader.ExcelWorkbookParser;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DynamicImportFacade {
    private final ExcelWorkbookParser parser;
    private final DynamicImportPlanBuilder planBuilder;
    private final ImportWorkbookGrouper grouper;
    private final DynamicImportExecutor executor;
    private final DynamicImportErrorWorkbookBuilder errorWorkbookBuilder;
    private final ExcelWorkbookPlanWriter workbookWriter;

    @Autowired
    public DynamicImportFacade(DynamicRecordService recordService) {
        this(new ExcelWorkbookParser(),
                new DynamicImportPlanBuilder(),
                new ImportWorkbookGrouper(),
                new DynamicImportExecutor(recordService),
                new DynamicImportErrorWorkbookBuilder(),
                new ExcelWorkbookPlanWriter());
    }

    DynamicImportFacade(ExcelWorkbookParser parser,
                        DynamicImportPlanBuilder planBuilder,
                        ImportWorkbookGrouper grouper,
                        DynamicImportExecutor executor,
                        DynamicImportErrorWorkbookBuilder errorWorkbookBuilder,
                        ExcelWorkbookPlanWriter workbookWriter) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.planBuilder = Objects.requireNonNull(planBuilder, "planBuilder must not be null");
        this.grouper = Objects.requireNonNull(grouper, "grouper must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.errorWorkbookBuilder = Objects.requireNonNull(errorWorkbookBuilder, "errorWorkbookBuilder must not be null");
        this.workbookWriter = Objects.requireNonNull(workbookWriter, "workbookWriter must not be null");
    }

    public DynamicImportParseResult parse(DynamicModuleDescriptor descriptor, byte[] excelBytes) {
        if (descriptor == null) {
            throw new PlatformException("dynamic import parse requires module descriptor");
        }
        if (excelBytes == null || excelBytes.length == 0) {
            throw new PlatformException("dynamic import parse excel bytes must not be empty");
        }
        ParsedWorkbook workbook = parser.parse(excelBytes);
        DynamicImportPlan plan = planBuilder.build(descriptor, workbook, parsePlanCommand(descriptor, workbook));
        return parseResult(plan, workbook);
    }

    @Transactional
    public DynamicImportResult importWorkbook(DynamicImportCommand command) {
        if (command == null) {
            throw new PlatformException("dynamic import command must not be null");
        }
        ParsedWorkbook workbook = parser.parse(command.excelBytes());
        DynamicImportPlan plan = planBuilder.build(command.descriptor(), workbook, command.buildPlanCommand());
        GroupedWorkbook groupedWorkbook = grouper.group(plan, workbook);
        DynamicImportExecutionResult executionResult =
                executor.execute(new ExecuteDynamicImportCommand(plan, groupedWorkbook));
        byte[] errorWorkbookBytes = buildErrorWorkbookBytes(plan, executionResult, workbook);
        return new DynamicImportResult(plan, groupedWorkbook, executionResult, errorWorkbookBytes);
    }

    private BuildDynamicImportPlanCommand parsePlanCommand(DynamicModuleDescriptor descriptor, ParsedWorkbook workbook) {
        Map<String, ParsedSheet> sheetsByEntity = sheetsByEntity(workbook);
        DynamicImportPlan initialPlan = planBuilder.build(descriptor, workbook,
                initialPlanCommand(descriptor, workbook, sheetsByEntity));
        List<BuildDynamicImportPlanCommand.ChildSheetCommand> childCommands = initialPlan.sheets().stream()
                .filter(sheet -> !sheet.main())
                .map(sheet -> new BuildDynamicImportPlanCommand.ChildSheetCommand(
                        sheet.entityAlias(),
                        firstMatchKeyCandidate(sheet),
                        ImportDuplicateStrategy.ERROR
                ))
                .toList();
        return new BuildDynamicImportPlanCommand(descriptor.moduleAlias(),
                firstMatchKeyCandidate(initialPlan.mainSheet()),
                ImportDuplicateStrategy.ERROR,
                childCommands);
    }

    private BuildDynamicImportPlanCommand initialPlanCommand(DynamicModuleDescriptor descriptor,
                                                            ParsedWorkbook workbook,
                                                            Map<String, ParsedSheet> sheetsByEntity) {
        String mainEntityAlias = descriptor.mainEntityAlias();
        ParsedSheet mainSheet = sheetsByEntity.get(mainEntityAlias);
        if (mainSheet == null) {
            throw new PlatformException("dynamic import main sheet not found: " + mainEntityAlias);
        }
        List<BuildDynamicImportPlanCommand.ChildSheetCommand> childCommands = firstLevelChildEntityAliases(descriptor)
                .stream()
                .filter(sheetsByEntity::containsKey)
                .map(entityAlias -> new BuildDynamicImportPlanCommand.ChildSheetCommand(
                        entityAlias,
                        firstBusinessFieldName(sheetsByEntity.get(entityAlias)),
                        ImportDuplicateStrategy.ERROR
                ))
                .toList();
        return new BuildDynamicImportPlanCommand(descriptor.moduleAlias(),
                firstBusinessFieldName(mainSheet),
                ImportDuplicateStrategy.ERROR,
                childCommands);
    }

    private Map<String, ParsedSheet> sheetsByEntity(ParsedWorkbook workbook) {
        Map<String, ParsedSheet> result = new LinkedHashMap<>();
        for (ParsedSheet sheet : workbook.sheets()) {
            result.put(sheet.entityAlias(), sheet);
        }
        return result;
    }

    private Set<String> firstLevelChildEntityAliases(DynamicModuleDescriptor descriptor) {
        Set<String> childEntityAliases = new LinkedHashSet<>();
        for (DynamicRelationDescriptor relation : descriptor.relations()) {
            if (relation == null || !Objects.equals(relation.parentEntityAlias(), descriptor.mainEntityAlias())) {
                continue;
            }
            if (relation.childEntityAlias() != null && !relation.childEntityAlias().isBlank()) {
                childEntityAliases.add(relation.childEntityAlias());
            }
        }
        return childEntityAliases;
    }

    private String firstBusinessFieldName(ParsedSheet sheet) {
        return sheet.columns().stream()
                .map(column -> column.fieldName())
                .filter(fieldName -> !ExcelExchangeProtocol.RELATE_ID_FIELD.equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new PlatformException("dynamic import sheet has no business field: "
                        + sheet.sheetName()));
    }

    private String firstMatchKeyCandidate(DynamicImportPlan.SheetPlan sheet) {
        return sheet.fields().stream()
                .filter(DynamicImportPlan.FieldPlan::matchKeyCandidate)
                .map(DynamicImportPlan.FieldPlan::fieldName)
                .findFirst()
                .orElseThrow(() -> new PlatformException("dynamic import sheet has no match key candidate: "
                        + sheet.sheetName()));
    }

    private DynamicImportParseResult parseResult(DynamicImportPlan plan, ParsedWorkbook workbook) {
        Map<String, ParsedSheet> parsedSheets = sheetsByEntity(workbook);
        DynamicImportPlan.SheetPlan mainSheet = plan.mainSheet();
        return new DynamicImportParseResult(
                plan.moduleAlias(),
                mainSheet.entityAlias(),
                mainSheet.sheetName(),
                plan.sheets().stream()
                        .map(sheet -> parseSheetResult(sheet, parsedSheets.get(sheet.entityAlias())))
                        .toList()
        );
    }

    private DynamicImportParseResult.Sheet parseSheetResult(DynamicImportPlan.SheetPlan sheet, ParsedSheet parsedSheet) {
        int rowCount = parsedSheet == null ? 0 : parsedSheet.rows().size();
        return new DynamicImportParseResult.Sheet(
                sheet.sheetKey(),
                sheet.sheetName(),
                sheet.entityAlias(),
                sheet.main(),
                rowCount,
                sheet.fields().stream()
                        .map(field -> new DynamicImportParseResult.Field(
                                field.fieldName(),
                                field.title(),
                                field.relateId(),
                                field.matchKeyCandidate()
                        ))
                        .toList()
        );
    }

    private byte[] buildErrorWorkbookBytes(DynamicImportPlan plan,
                                           DynamicImportExecutionResult executionResult,
                                           ParsedWorkbook workbook) {
        if (executionResult.errorRows().isEmpty()) {
            return null;
        }
        ExcelWorkbookPlan errorWorkbook = errorWorkbookBuilder.buildRetryWorkbook(plan, executionResult.errorRows(), workbook);
        return workbookWriter.writeToBytes(errorWorkbook);
    }
}
