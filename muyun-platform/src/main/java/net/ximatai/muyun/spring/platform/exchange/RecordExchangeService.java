package net.ximatai.muyun.spring.platform.exchange;

import static net.ximatai.muyun.spring.platform.exchange.RecordExchangeWorkbook.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.platform.content.BusinessContentTranslationService;
import net.ximatai.muyun.spring.platform.exchange.importer.*;
import net.ximatai.muyun.spring.platform.exchange.model.*;
import net.ximatai.muyun.spring.platform.exchange.reader.ExcelWorkbookParser;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import net.ximatai.muyun.spring.platform.save.RecordSaveReceiptService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

/**
 * One bounded, synchronous file contract for static and dynamic producers; translation never
 * participates in a business save.
 */
@Service
public final class RecordExchangeService {
    public static final int MAX_ROWS = 1000, MAX_BYTES = 8 * 1024 * 1024;
    private final RecordExchangeReceipts receipts;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ExcelWorkbookParser parser = new ExcelWorkbookParser(true);
    private final ExcelWorkbookPlanWriter writer = new ExcelWorkbookPlanWriter();
    private final DynamicImportValueConverter converter = new DynamicImportValueConverter();

    public RecordExchangeService(
            RecordExchangeReceipts receipts,
            ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.receipts = Objects.requireNonNull(receipts);
        this.json = Objects.requireNonNull(json);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Export(String purpose, String language, String timeZone) {
        public Export {
            purpose = purpose == null ? DATA : purpose;
            if (!Set.of(DATA, TRANSLATIONS, READING).contains(purpose))
                throw new IllegalArgumentException("Unknown file purpose");
            language = language == null ? null : LanguageCatalog.canonicalTag(language);
            if (!DATA.equals(purpose) && language == null)
                throw new IllegalArgumentException("Choose the file language");
            timeZone = timeZone == null ? "UTC" : timeZone;
            net.ximatai.muyun.spring.common.time.PlatformTimeService.requireIanaZoneId(timeZone);
        }
    }

    public record SheetCommand(
            String entityAlias, String matchField, ImportDuplicateStrategy duplicateStrategy) {
        public SheetCommand {
            if (entityAlias == null
                    || entityAlias.isBlank()
                    || matchField == null
                    || matchField.isBlank())
                throw new IllegalArgumentException("Choose an entity and matching field");
            duplicateStrategy =
                    duplicateStrategy == null ? ImportDuplicateStrategy.ERROR : duplicateStrategy;
        }
    }

    public record Confirmation(String row, String field, String input, String candidateId) {
        public Confirmation {
            if (row == null
                    || row.isBlank()
                    || row.length() > 512
                    || field == null
                    || field.isBlank()
                    || field.length() > 128
                    || input == null
                    || input.length() > 32768
                    || candidateId == null
                    || candidateId.isBlank()
                    || candidateId.length() > 2048)
                throw new IllegalArgumentException("Invalid reference object confirmation");
        }
    }

    public record Command(
            String documentId,
            String previewDigest,
            List<SheetCommand> sheets,
            List<Confirmation> confirmations,
            String sourceLanguage,
            String resolutionLanguage) {
        public Command(
                String documentId,
                String previewDigest,
                List<SheetCommand> sheets,
                List<Confirmation> confirmations,
                String sourceLanguage) {
            this(documentId, previewDigest, sheets, confirmations, sourceLanguage, null);
        }

        public Command {
            sheets = sheets == null ? List.of() : List.copyOf(sheets);
            confirmations = confirmations == null ? List.of() : List.copyOf(confirmations);
            if (sheets.size() > 50 || confirmations.size() > MAX_ROWS * 20)
                throw new IllegalArgumentException("File command is too large");
            if (sheets.stream().map(SheetCommand::entityAlias).distinct().count() != sheets.size()
                    || confirmations.stream()
                                    .map(
                                            choice ->
                                                    List.of(
                                                            choice.row(),
                                                            choice.field(),
                                                            choice.input()))
                                    .distinct()
                                    .count()
                            != confirmations.size())
                throw new IllegalArgumentException(
                        "Duplicate file strategy or reference confirmation");
            sourceLanguage =
                    sourceLanguage == null ? null : LanguageCatalog.canonicalTag(sourceLanguage);
            resolutionLanguage =
                    resolutionLanguage == null
                            ? null
                            : LanguageCatalog.canonicalTag(resolutionLanguage);
        }

        SheetCommand sheet(String entity) {
            return sheets.stream()
                    .filter(sheet -> entity.equals(sheet.entityAlias()))
                    .findFirst()
                    .orElse(null);
        }
    }

    public record ReferenceChoice(
            String field, String input, List<RecordExchangeAccess.Candidate> candidates) {}

    public record PreviewRow(
            String row,
            String entity,
            String relation,
            String operation,
            String recordId,
            Map<String, Object> currentValues,
            Map<String, Object> changes,
            Map<String, SourceInput> languages,
            List<ReferenceChoice> choices,
            String problem,
            Map<String, ReferenceDisplay> references) {
        public PreviewRow(String row, String entity, String relation, String operation, String recordId,
                          Map<String, Object> currentValues, Map<String, Object> changes,
                          Map<String, SourceInput> languages, List<ReferenceChoice> choices, String problem) {
            this(row, entity, relation, operation, recordId, currentValues, changes, languages, choices, problem, Map.of());
        }
    }

    public record ReferenceDisplay(String current, String submitted) {}

    public record Preview(
            String documentId,
            String previewDigest,
            String purpose,
            String language,
            List<Sheet> sheets,
            List<PreviewRow> rows) {}

    public record Sheet(
            String entityAlias,
            String sheetName,
            boolean main,
            int rowCount,
            List<String> matchFields,
            Map<String, String> fieldTitles,
            String recommendedMatchField) {
        public Sheet(String entityAlias, String sheetName, boolean main, int rowCount,
                     List<String> matchFields, Map<String, String> fieldTitles) {
            this(entityAlias, sheetName, main, rowCount, matchFields, fieldTitles, null);
        }
    }

    public record Result(
            int created,
            int updated,
            int skipped,
            int errorCount,
            boolean partialSuccess,
            String errorWorkbookBase64,
            List<PreviewRow> rows) {}

    public record Baseline(
            Map<String, String> hashes,
            Map<String, Object> references,
            Map<String, Integer> sourceRevisions,
            String field,
            String originalId,
            Integer originalRevision,
            Integer translationRevision) {
        public Baseline {
            hashes = Map.copyOf(hashes);
            references = Map.copyOf(references);
            sourceRevisions = Map.copyOf(sourceRevisions);
        }

        static Baseline empty() {
            return new Baseline(Map.of(), Map.of(), Map.of(), null, null, null, null);
        }
    }

    private record Input(
            RecordExchangeDefinition.Entity entity,
            ParsedSheet sheet,
            Map<String, String> raw,
            String key) {}

    private record Planned(
            Input input,
            RecordExchangeReceipt receipt,
            RecordExchangeAccess.Row existing,
            Map<String, Object> changes,
            Map<String, SourceInput> languages,
            List<ReferenceChoice> choices,
            String operation) {}

    private record Saved(
            RecordExchangeAccess.Row row, RecordExchangeReceipt receipt, String operation) {}

    public byte[] template(RecordExchangeAccess access, Export options) {
        if (!DATA.equals(options.purpose()))
            throw new IllegalArgumentException(
                    "Use selected business records for translation or reading export");
        var document =
                tx(
                        () ->
                                receipts.document(
                                        access.definition().moduleAlias(),
                                        DATA,
                                        options.language()));
        return writer.writeToBytes(
                new ExcelWorkbookPlan(
                        meta(document, options.timeZone()),
                        sheets(access, DATA, options.language(), Map.of())));
    }

    /**
     * Root records are selected by the caller's already compiled query; every row is reopened under
     * current producer authorization.
     */
    public byte[] export(RecordExchangeAccess access, List<String> ids, Export options) {
        if (ids == null || ids.size() > MAX_ROWS)
            throw new IllegalArgumentException("Choose at most 1000 records for export");
        return tx(
                () -> {
                    var definition = access.definition();
                    var document =
                            receipts.document(
                                    definition.moduleAlias(),
                                    options.purpose(),
                                    options.language());
                    var output = new LinkedHashMap<String, List<Map<String, Object>>>();
                    int count = 0;
                    for (String id : new LinkedHashSet<>(ids)) {
                        var root = access.read(definition.main().alias(), null, id);
                        count += exportRow(access, document, definition.main(), null, root, output);
                        if (count > MAX_ROWS)
                            throw new IllegalArgumentException(
                                    "File exceeds 1000 rows; narrow the selection");
                        for (var child : definition.entities())
                            if (!child.main()) {
                                var children =
                                        access.children(child.alias(), id, MAX_ROWS - count + 1);
                                for (var row : children)
                                    count += exportRow(access, document, child, id, row, output);
                                if (count > MAX_ROWS)
                                    throw new IllegalArgumentException(
                                            "File exceeds 1000 rows; narrow the selection");
                            }
                    }
                    return writer.writeToBytes(
                            new ExcelWorkbookPlan(
                                    meta(document, options.timeZone()),
                                    sheets(access, options.purpose(), options.language(), output)));
                });
    }

    public Preview preview(RecordExchangeAccess access, byte[] bytes, Command command) {
        var workbook = parse(bytes);
        var document = document(access, workbook, command, true);
        var inputs = inputs(access.definition(), workbook, document.getPurpose());
        var output = new ArrayList<PreviewRow>();
        var parents = new HashMap<String, String>();
        var reviewedParents = new HashSet<String>();
        for (var input : inputs) {
            String parent = translationParent(document, input);
            if (parent == null && !input.entity().main())
                parent = parents.get(input.raw().get(RELATE));
            try {
                if (!input.entity().main()
                        && !TRANSLATIONS.equals(document.getPurpose())
                        && !reviewedParents.contains(input.raw().get(RELATE)))
                    throw new IllegalArgumentException("先修正并核对主表记录 / Review the parent row first");
                var planned = plan(access, document, input, parent, command, workbook);
                output.add(view(access, planned, parent, importLanguage(document, workbook, command)));
                if (command != null
                        && (command.sheet(input.entity().alias()) != null
                                || TRANSLATIONS.equals(document.getPurpose())))
                    freeze(access, document, planned, parent);
                if (input.entity().main() && planned.existing() != null)
                    parents.put(input.raw().get(RELATE), planned.existing().id());
                if (input.entity().main()) reviewedParents.add(input.raw().get(RELATE));
            } catch (RuntimeException failure) {
                if (!collectable(failure)) throw failure;
                output.add(problem(input, failure));
            }
        }
        return new Preview(
                document.getId(),
                digest(bytes, command, document.getId()),
                document.getPurpose(),
                importLanguage(document, workbook, command),
                workbook.sheets().stream()
                        .map(
                                sheet ->
                                        new Sheet(
                                                sheet.entityAlias(),
                                                sheet.sheetName(),
                                                access.definition()
                                                        .entity(sheet.entityAlias())
                                                        .main(),
                                                sheet.rows().size(),
                                                access
                                                        .definition()
                                                        .entity(sheet.entityAlias())
                                                        .fields()
                                                        .stream()
                                                        .filter(
                                                                field ->
                                                                        field.writable()
                                                                                && !field
                                                                                        .companion())
                                                        .map(RecordExchangeDefinition.Field::name)
                                                        .toList(),
                                                access
                                                        .definition()
                                                        .entity(sheet.entityAlias())
                                                        .fields()
                                                        .stream()
                                                        .collect(
                                                                java.util.stream.Collectors.toMap(
                                                                        RecordExchangeDefinition
                                                                                        .Field
                                                                                ::name,
                                                                        RecordExchangeDefinition
                                                                                        .Field
                                                                                ::title)),
                                                access.definition().entity(sheet.entityAlias()).fields().stream()
                                                        .filter(field -> field.writable() && !field.companion() && field.matchingKey())
                                                        .map(RecordExchangeDefinition.Field::name).findFirst().orElse(null)))
                        .toList(),
                output);
    }

    /**
     * Compatibility file download rechecks each retained source and current field exposure before
     * returning any bytes.
     */
    public void requireFileRead(RecordExchangeAccess access, byte[] bytes) {
        var workbook = parse(bytes);
        var document = document(access, workbook, null, false);
        var parents = new HashMap<String, String>();
        for (var input : inputs(access.definition(), workbook, document.getPurpose())) {
            RecordExchangeReceipt receipt =
                    text(input.raw().get(ROW)) == null
                            ? null
                            : receipts.row(document, input.raw().get(ROW), input.entity().alias());
            if (receipt == null)
                receipt =
                        receipts.newRowIfPresent(
                                document, generatedRowId(document, input), input.entity().alias());
            var fields =
                    input.entity().fields().stream()
                            .filter(field -> text(input.raw().get(field.name())) != null)
                            .map(RecordExchangeDefinition.Field::name)
                            .collect(java.util.stream.Collectors.toSet());
            if (receipt != null && receipt.getRecordId() != null) {
                var record =
                        access.read(
                                input.entity().alias(),
                                receipt.getParentId(),
                                receipt.getRecordId());
                if (!record.values().keySet().containsAll(fields))
                    throw new net.ximatai.muyun.spring.common.exception
                            .PlatformAccessDeniedException("File fields are unavailable");
                if (input.entity().main()) parents.put(input.raw().get(RELATE), record.id());
            } else
                access.requireNewRowRead(
                        input.entity().alias(), parents.get(input.raw().get(RELATE)), fields);
        }
    }

    public Result execute(RecordExchangeAccess access, byte[] bytes, Command command) {
        Objects.requireNonNull(command, "Import confirmation is required");
        var workbook = parse(bytes);
        var document = document(access, workbook, command, false);
        if (!digest(bytes, command, document.getId()).equals(command.previewDigest()))
            throw new IllegalArgumentException(
                    "File or import options changed; preview them again before importing");
        if (DATA.equals(document.getPurpose()))
            return executeData(access, workbook, document, command);
        var inputs = inputs(access.definition(), workbook, document.getPurpose());
        var parents = new HashMap<String, Saved>();
        var failures = new LinkedHashMap<Input, String>();
        var output = new ArrayList<PreviewRow>();
        int created = 0, updated = 0, skipped = 0;
        for (var input : inputs) {
            Saved parent = input.entity().main() ? null : parents.get(input.raw().get(RELATE));
            String recordedParent = translationParent(document, input);
            if (recordedParent != null)
                parent =
                        new Saved(
                                access.read(
                                        access.definition().main().alias(), null, recordedParent),
                                null,
                                "CONTEXT");
            if (!input.entity().main() && parent == null) {
                failures.put(input, "主表未成功处理，请先修正主表 / Parent row was not processed");
                continue;
            }
            try {
                final Saved owningParent = parent;
                var saved =
                        tx(
                                () -> {
                                    receipts.lock(document, input.key());
                                    var planned =
                                            plan(
                                                    access,
                                                    document,
                                                    input,
                                                    owningParent == null
                                                            ? null
                                                            : owningParent.row().id(),
                                                    command,
                                                    workbook);
                                    if (planned.receipt() == null
                                            || planned.receipt().getPlannedOperation() == null)
                                        throw new IllegalArgumentException(
                                                "Preview every file row with the selected import"
                                                        + " options before importing");
                                    if (!planned.choices().isEmpty())
                                        throw new IllegalArgumentException(
                                                "请明确确认引用业务对象后导入 / Confirm the reference object"
                                                        + " before importing");
                                    var row = planned.existing();
                                    var receipt = planned.receipt();
                                    String operation = planned.operation();
                                    if (!Set.of("CONTEXT", "SKIP").contains(operation)) {
                                        row = saveTranslation(access, document, planned);
                                    }
                                    if (row == null)
                                        throw new IllegalArgumentException(
                                                "File context record is unavailable");
                                    if (receipt == null)
                                        receipt =
                                                receipts.createRow(
                                                        document,
                                                        generatedRowId(document, input),
                                                        input.entity().alias(),
                                                        owningParent == null
                                                                ? null
                                                                : owningParent.row().id(),
                                                        row,
                                                        encode(Baseline.empty()));
                                    if (receipt.getCommittedDigest() == null) {
                                        if (receipt.getParentId() == null && owningParent != null)
                                            receipt.setParentId(owningParent.row().id());
                                        receipts.committed(receipt, row, rowDigest(input));
                                    }
                                    return new Saved(row, receipt, operation);
                                });
                if (input.entity().main()) parents.put(input.raw().get(RELATE), saved);
                if ("CREATE".equals(saved.operation())) created++;
                else if (Set.of("UPDATE", "DRAFT").contains(saved.operation())) updated++;
                else skipped++;
                output.add(
                        new PreviewRow(
                                input.key(),
                                input.entity().alias(),
                                input.raw().get(RELATE),
                                saved.operation(),
                                saved.row().id(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                List.of(),
                                null));
            } catch (RuntimeException failure) {
                if (!collectable(failure)) throw failure;
                failures.put(input, message(failure));
                output.add(problem(input, failure));
            }
        }
        byte[] error =
                failures.isEmpty()
                        ? null
                        : retryWorkbook(
                                access, document, workbook, inputs, failures, parents, command);
        return new Result(
                created,
                updated,
                skipped,
                failures.size(),
                created + updated > 0 && !failures.isEmpty(),
                error == null ? null : Base64.getEncoder().encodeToString(error),
                List.copyOf(output));
    }

    private Result executeData(
            RecordExchangeAccess access,
            ParsedWorkbook workbook,
            RecordExchangeReceipt document,
            Command command) {
        var inputs = inputs(access.definition(), workbook, DATA);
        var groups = new LinkedHashMap<String, List<Input>>();
        inputs.forEach(
                input ->
                        groups.computeIfAbsent(
                                        input.raw().get(RELATE), ignored -> new ArrayList<>())
                                .add(input));
        var failures = new LinkedHashMap<Input, String>();
        var contexts = new HashMap<String, Saved>();
        var output = new ArrayList<PreviewRow>();
        int created = 0, updated = 0, skipped = 0, errors = 0;
        for (var group : groups.values()) {
            try {
                var saved = tx(() -> saveGroup(access, workbook, document, command, group));
                for (var row : saved.entrySet()) {
                    String operation = row.getValue().operation();
                    if ("CREATE".equals(operation)) created++;
                    else if ("UPDATE".equals(operation)) updated++;
                    else skipped++;
                    output.add(
                            new PreviewRow(
                                    row.getKey().key(),
                                    row.getKey().entity().alias(),
                                    row.getKey().raw().get(RELATE),
                                    operation,
                                    row.getValue().row().id(),
                                    Map.of(),
                                    Map.of(),
                                    Map.of(),
                                    List.of(),
                                    null));
                }
            } catch (GroupFailure failure) {
                if (!collectable(failure.failure)) throw failure.failure;
                for (var input : group) {
                    var plan =
                            failure.planned.stream()
                                    .filter(value -> value.input().equals(input))
                                    .findFirst()
                                    .orElse(null);
                    boolean context =
                            plan != null
                                    && Set.of("SKIP", "CONTEXT").contains(plan.operation())
                                    && plan.existing() != null;
                    if (context)
                        contexts.put(
                                input.entity().alias() + ":" + input.key(),
                                new Saved(plan.existing(), plan.receipt(), "CONTEXT"));
                    else errors++;
                    failures.put(
                            input,
                            "关联业务单元未提交 / Aggregate was not submitted: " + message(failure.failure));
                    if (context)
                        output.add(
                                new PreviewRow(
                                        input.key(),
                                        input.entity().alias(),
                                        input.raw().get(RELATE),
                                        "CONTEXT",
                                        plan.existing().id(),
                                        Map.of(),
                                        Map.of(),
                                        Map.of(),
                                        List.of(),
                                        null));
                    else output.add(problem(input, failure.failure));
                }
            }
        }
        byte[] error =
                failures.isEmpty()
                        ? null
                        : retryWorkbook(
                                access, document, workbook, inputs, failures, contexts, command);
        return new Result(
                created,
                updated,
                skipped,
                errors,
                created + updated > 0 && !failures.isEmpty(),
                error == null ? null : Base64.getEncoder().encodeToString(error),
                List.copyOf(output));
    }

    private Map<Input, Saved> saveGroup(
            RecordExchangeAccess access,
            ParsedWorkbook workbook,
            RecordExchangeReceipt document,
            Command command,
            List<Input> inputs) {
        var planned = new ArrayList<Planned>();
        try {
            inputs.stream()
                    .sorted(Comparator.comparing(Input::key))
                    .forEach(input -> receipts.lock(document, input.key()));
            var rootInput =
                    inputs.stream()
                            .filter(input -> input.entity().main())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "关联主表缺失 / Parent row is required"));
            var root = requirePlanned(plan(access, document, rootInput, null, command, workbook));
            planned.add(root);
            RuntimeException problem =
                    root.choices().isEmpty()
                            ? null
                            : new IllegalArgumentException(
                                    "请确认引用业务对象 / Confirm the reference object");
            for (var input : inputs)
                if (!input.entity().main()) {
                    try {
                        var child =
                                requirePlanned(
                                        plan(
                                                access,
                                                document,
                                                input,
                                                root.existing() == null
                                                        ? null
                                                        : root.existing().id(),
                                                command,
                                                workbook));
                        if (child.existing() != null
                                && (root.existing() == null
                                        || !Objects.equals(
                                                child.receipt().getParentId(),
                                                root.existing().id())))
                            throw new IllegalArgumentException(
                                    "明细不属于本次主表 / Child belongs to a different parent");
                        planned.add(child);
                        if (!child.choices().isEmpty() && problem == null)
                            problem =
                                    new IllegalArgumentException(
                                            "请确认引用业务对象 / Confirm the reference object");
                    } catch (RuntimeException invalid) {
                        if (!collectable(invalid)) throw invalid;
                        if (problem == null) problem = invalid;
                    }
                }
            if (problem != null) throw problem;
            var identities = new HashSet<List<Object>>();
            for (var row : planned) {
                var strategy = command.sheet(row.input().entity().alias());
                if (strategy == null || "EXPORTED".equals(row.receipt().getPlannedOperation()))
                    continue;
                Object identity =
                        row.existing() == null
                                ? row.changes().get(strategy.matchField())
                                : row.existing().id();
                if (identity != null
                        && !identities.add(List.of(row.input().entity().alias(), identity)))
                    throw new IllegalArgumentException(
                            "同一业务单元存在重复匹配值，请补充唯一编码 / Duplicate matching values in the same business"
                                    + " unit");
            }
            var saved =
                    access.saveAggregate(
                            change(root),
                            planned.stream()
                                    .filter(row -> !row.input().entity().main())
                                    .map(this::change)
                                    .toList());
            var result = new LinkedHashMap<Input, Saved>();
            var parent =
                    Objects.requireNonNull(saved.get(root.input().key()), "Saved parent identity");
            for (var row : planned) {
                var record =
                        Objects.requireNonNull(saved.get(row.input().key()), "Saved row identity");
                var receipt = row.receipt();
                if (receipt.getCommittedDigest() == null) {
                    if (!row.input().entity().main()) receipt.setParentId(parent.id());
                    receipts.committed(receipt, record, rowDigest(row.input()));
                }
                result.put(row.input(), new Saved(record, receipt, row.operation()));
            }
            return result;
        } catch (RuntimeException failure) {
            throw new GroupFailure(failure, planned);
        }
    }

    private Planned requirePlanned(Planned planned) {
        if (planned.receipt() == null || planned.receipt().getPlannedOperation() == null)
            throw new IllegalArgumentException(
                    "Preview each row with the chosen options before importing");
        return planned;
    }

    private RecordExchangeAccess.AggregateChange change(Planned row) {
        return new RecordExchangeAccess.AggregateChange(
                row.input().key(),
                row.input().entity().alias(),
                row.existing(),
                row.changes(),
                row.languages(),
                row.operation());
    }

    private static final class GroupFailure extends RuntimeException {
        final RuntimeException failure;
        final List<Planned> planned;

        GroupFailure(RuntimeException failure, List<Planned> planned) {
            super(failure);
            this.failure = failure;
            this.planned = List.copyOf(planned);
        }
    }

    private Planned plan(
            RecordExchangeAccess access,
            RecordExchangeReceipt document,
            Input input,
            String parent,
            Command command,
            ParsedWorkbook workbook) {
        if (READING.equals(document.getPurpose()))
            throw new IllegalArgumentException(
                    "阅读导出不能回导为业务原文 / Reading exports are not editable business data");
        var entity = input.entity();
        RecordExchangeReceipt receipt = null;
        if (text(input.raw().get(ROW)) != null)
            receipt = receipts.row(document, input.raw().get(ROW), entity.alias());
        else
            receipt =
                    receipts.newRowIfPresent(
                            document, generatedRowId(document, input), entity.alias());
        if (receipt != null
                && receipt.getParentId() != null
                && !Objects.equals(receipt.getParentId(), parent)
                && (!entity.main() && parent != null))
            throw new IllegalArgumentException("File row belongs to a different parent");
        RecordExchangeAccess.Row existing =
                receipt == null || receipt.getRecordId() == null
                        ? null
                        : access.read(entity.alias(), receipt.getParentId(), receipt.getRecordId());
        if (receipt != null
                && existing != null
                && !Objects.equals(receipt.getRecordCreatedAt(), existing.createdAt()))
            throw new IllegalArgumentException("Record was replaced; export a new file");
        if ("true".equalsIgnoreCase(input.raw().get(CONTEXT))) {
            if (receipt == null
                    || existing == null
                    || receipt.getCommittedDigest() == null
                            && !Set.of("EXPORTED", "SKIP", "CONTEXT")
                                    .contains(receipt.getPlannedOperation()))
                throw new IllegalArgumentException("Unverified context-only row");
            if (receipt.getCommittedDigest() == null
                    && !Objects.equals(receipt.getRecordVersion(), existing.version()))
                throw new IllegalArgumentException(
                        "关联上下文已修改，请重新导出 / Context record version changed");
            if (text(input.raw().get(CLEAR)) != null
                    || entity.fields().stream()
                            .anyMatch(
                                    field ->
                                            text(input.raw().get(field.name())) != null
                                                    || text(input.raw().get(source(field.name())))
                                                            != null))
                throw new IllegalArgumentException(
                        "关联上下文只读，修改业务请重新导出 / Context rows are read-only");
            return new Planned(input, receipt, existing, Map.of(), Map.of(), List.of(), "CONTEXT");
        }
        if (receipt != null && receipt.getCommittedDigest() != null) {
            if (!receipt.getCommittedDigest().equals(rowDigest(input)))
                throw new IllegalArgumentException(
                        "This row was already imported; export a new edit file to change it");
            return new Planned(input, receipt, existing, Map.of(), Map.of(), List.of(), "SKIP");
        }
        if (TRANSLATIONS.equals(document.getPurpose()))
            return planTranslation(access, document, input, receipt, existing);
        var changes =
                convert(access, document, entity, input, workbook, command, baseline(receipt));
        var baseline = baseline(receipt);
        var sheet = command == null ? null : command.sheet(entity.alias());
        if (existing == null && sheet != null && !entity.field(sheet.matchField()).reference())
            existing = match(access, input, parent, sheet, changes, receipt);
        if (existing != null
                && receipt != null
                && !Objects.equals(receipt.getRecordVersion(), existing.version()))
            throw new IllegalArgumentException("业务已被修改，请重新导出并复核 / Record version changed");
        var referenceValues = new LinkedHashMap<String, Object>();
        if (existing != null) referenceValues.putAll(existing.values());
        referenceValues.putAll(changes);
        var choices = new ArrayList<ReferenceChoice>();
        for (var field : entity.fields()) {
            String raw =
                    field.reference()
                            ? text(input.raw().get(field.name()))
                            : nonblank(input.raw().get(field.name()));
            if (raw == null) continue;
            if (field.companion()) {
                if (Objects.equals(
                        baseline.hashes().get(field.name()),
                        fingerprint(field, changes.get(field.name()))))
                    changes.remove(field.name());
                continue;
            }
            if (!field.writable())
                throw new IllegalArgumentException("Field is not writable: " + field.title());
            boolean changedZone =
                    field.type()
                                    == net.ximatai.muyun.spring.dynamic.metadata.FieldType
                                            .ZONED_TIMESTAMP
                            && changes.containsKey(
                                    net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRules
                                            .zonedTimestampTimeZoneFieldName(field.name()))
                            && !Objects.equals(
                                    baseline.hashes()
                                            .get(
                                                    net.ximatai.muyun.spring.dynamic.metadata
                                                            .FieldCompanionRules
                                                            .zonedTimestampTimeZoneFieldName(
                                                                    field.name())),
                                    fingerprint(
                                            entity.field(
                                                    net.ximatai.muyun.spring.dynamic.metadata
                                                            .FieldCompanionRules
                                                            .zonedTimestampTimeZoneFieldName(
                                                                    field.name())),
                                            changes.get(
                                                    net.ximatai.muyun.spring.dynamic.metadata
                                                            .FieldCompanionRules
                                                            .zonedTimestampTimeZoneFieldName(
                                                                    field.name()))));
            if (!changedZone
                    && Objects.equals(
                            baseline.hashes().get(field.name()),
                            fingerprint(
                                    field, field.reference() ? raw : changes.get(field.name())))) {
                changes.remove(field.name());
                continue;
            }
            if (field.reference()) {
                var resolved =
                        access.resolve(
                                entity.alias(),
                                parent,
                                field.name(),
                                raw,
                                importLanguage(document, workbook, command),
                                referenceValues);
                var candidates = resolved.candidates();
                if (candidates.isEmpty())
                    throw new IllegalArgumentException(
                            "引用名称未找到，请修正 / Reference not found: " + field.title());
                var confirmation =
                        command == null
                                ? null
                                : command.confirmations().stream()
                                        .filter(
                                                choice ->
                                                        choice.row().equals(input.key())
                                                                && choice.field()
                                                                        .equals(field.name())
                                                                && choice.input().equals(raw))
                                        .findFirst()
                                        .orElse(null);
                if (confirmation != null) {
                    var candidate =
                            candidates.stream()
                                    .filter(value -> value.id().equals(confirmation.candidateId()))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Reference candidates changed; confirm"
                                                                    + " the object again"));
                    changes.put(field.name(), candidate.id());
                } else if (candidates.size() == 1
                        && !"MACHINE".equals(candidates.getFirst().kind()))
                    changes.put(field.name(), candidates.getFirst().id());
                else {
                    choices.add(new ReferenceChoice(field.name(), raw, candidates));
                    changes.remove(field.name());
                }
            }
        }
        Set<String> clear = split(input.raw().get(CLEAR));
        for (String name : clear) {
            var field = entity.field(name);
            if (!field.writable() || field.companion())
                throw new IllegalArgumentException("Field cannot be cleared: " + field.title());
            if (text(input.raw().get(name)) != null)
                throw new IllegalArgumentException("Clear and value conflict: " + field.title());
            changes.put(name, null);
            if (field.type() == net.ximatai.muyun.spring.dynamic.metadata.FieldType.ZONED_TIMESTAMP)
                changes.put(
                        net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRules
                                .zonedTimestampTimeZoneFieldName(name),
                        null);
        }
        if (existing == null
                && sheet != null
                && entity.field(sheet.matchField()).reference()
                && choices.isEmpty())
            existing = match(access, input, parent, sheet, changes, receipt);
        if (existing != null && receipt != null && "CREATE".equals(receipt.getPlannedOperation()))
            throw new IllegalArgumentException(
                    "A matching record was created after review; preview a new file");
        if (existing != null
                && sheet != null
                && (receipt == null
                        || receipt.getPlannedOperation() != null
                                && !"EXPORTED".equals(receipt.getPlannedOperation()))) {
            if (sheet.duplicateStrategy() == ImportDuplicateStrategy.ERROR)
                throw new IllegalArgumentException("业务记录已存在 / Business record already exists");
            if (sheet.duplicateStrategy() == ImportDuplicateStrategy.SKIP)
                return new Planned(input, receipt, existing, Map.of(), Map.of(), choices, "SKIP");
        }
        if (existing != null
                && receipt != null
                && !Objects.equals(receipt.getRecordVersion(), existing.version()))
            throw new IllegalArgumentException("业务已被修改，请重新导出并复核 / Record version changed");
        if (existing != null)
            for (var field : entity.fields())
                if (field.reference()
                        && !changes.containsKey(field.name())
                        && baseline.references().containsKey(field.name()))
                    access.requireReference(
                            entity.alias(),
                            parent,
                            field.name(),
                            baseline.references().get(field.name()),
                            existing.values());
        var languages = new LinkedHashMap<String, SourceInput>();
        for (var field : entity.fields())
            if (field.content() && changes.containsKey(field.name())) {
                String language = command == null ? null : command.sourceLanguage();
                if (language == null) language = text(input.raw().get(source(field.name())));
                if (language == null) language = text(input.raw().get(SOURCE));
                if (language == null
                        && existing != null
                        && Objects.equals(
                                existing.values().get(field.name()), changes.get(field.name()))) {
                    changes.remove(field.name());
                    continue;
                }
                languages.put(
                        field.name(),
                        new SourceInput(language, baseline.sourceRevisions().get(field.name())));
            }
        return new Planned(
                input,
                receipt,
                existing,
                Collections.unmodifiableMap(changes),
                Map.copyOf(languages),
                List.copyOf(choices),
                existing == null ? "CREATE" : changes.isEmpty() ? "SKIP" : "UPDATE");
    }

    private RecordExchangeAccess.Row match(
            RecordExchangeAccess access,
            Input input,
            String parent,
            SheetCommand sheet,
            Map<String, Object> changes,
            RecordExchangeReceipt receipt) {
        // A newly created parent has no persisted children to match during preview.
        if (!input.entity().main() && parent == null) return null;
        Object value = changes.get(sheet.matchField());
        if (value == null)
            throw new IllegalArgumentException("匹配字段不能为空 / Matching field is required");
        var matches = access.match(input.entity().alias(), parent, sheet.matchField(), value);
        if (matches.size() > 1)
            throw new IllegalArgumentException("匹配到多条业务记录，请补充编码或限定条件 / Ambiguous business record");
        if (!matches.isEmpty() && receipt != null && "CREATE".equals(receipt.getPlannedOperation()))
            throw new IllegalArgumentException(
                    "A matching record was created after review; export and review again");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private void freeze(
            RecordExchangeAccess access,
            RecordExchangeReceipt document,
            Planned planned,
            String parent) {
        tx(
                () -> {
                    receipts.lock(document, planned.input().key());
                    var receipt = planned.receipt();
                    if (receipt == null) {
                        var revisions = new LinkedHashMap<String, Integer>();
                        if (planned.existing() != null
                                && planned.input().entity().fields().stream()
                                        .anyMatch(RecordExchangeDefinition.Field::content))
                            access.sources(
                                            planned.input().entity().alias(),
                                            parent,
                                            planned.existing().id())
                                    .fields()
                                    .forEach(
                                            (field, source) -> {
                                                if (source.expectedRevision() != null)
                                                    revisions.put(field, source.expectedRevision());
                                            });
                        receipt =
                                receipts.newRowIfPresent(
                                        document,
                                        generatedRowId(document, planned.input()),
                                        planned.input().entity().alias());
                        if (receipt == null)
                            receipt =
                                    receipts.createRow(
                                            document,
                                            generatedRowId(document, planned.input()),
                                            planned.input().entity().alias(),
                                            parent,
                                            planned.existing(),
                                            encode(
                                                    new Baseline(
                                                            Map.of(), Map.of(), revisions, null,
                                                            null, null, null)));
                    }
                    if (receipt.getPlannedOperation() == null)
                        receipts.reviewed(receipt, planned.operation());
                    return null;
                });
    }

    private Planned planTranslation(
            RecordExchangeAccess access,
            RecordExchangeReceipt document,
            Input input,
            RecordExchangeReceipt receipt,
            RecordExchangeAccess.Row existing) {
        if (receipt == null || existing == null)
            throw new IllegalArgumentException(
                    "Translation rows require an exported original identity");
        var baseline = baseline(receipt);
        if (!Objects.equals(baseline.field(), input.raw().get(FIELD))
                || !Objects.equals(baseline.originalId(), input.raw().get(ORIGINAL_ID))
                || !Objects.equals(
                        String.valueOf(baseline.originalRevision()), input.raw().get(REVISION))
                || !Objects.equals(
                        baseline.translationRevision() == null
                                ? null
                                : baseline.translationRevision().toString(),
                        input.raw().get(TRANSLATION_REVISION))
                || !Objects.equals(
                        baseline.hashes().get(ORIGINAL),
                        nonblank(input.raw().get(ORIGINAL)) == null
                                ? null
                                : hash(input.raw().get(ORIGINAL)))
                || !document.getLanguageTag().equals(input.raw().get(TARGET)))
            throw new IllegalArgumentException("Translation identity was changed");
        var current =
                access.translation(
                        input.entity().alias(),
                        receipt.getParentId(),
                        existing.id(),
                        baseline.field(),
                        document.getLanguageTag(),
                        null);
        if (!current.original().revisionId().equals(baseline.originalId())
                || current.original().revision() != baseline.originalRevision())
            throw new IllegalArgumentException("原文已修订，请重新导出复核 / Original revision changed");
        if (!Objects.equals(current.expectedRevision(), baseline.translationRevision()))
            throw new IllegalArgumentException("译文已修改，请重新导出复核 / Translation version changed");
        String value = nonblank(input.raw().get(TEXT));
        boolean changed = value != null && !hash(value).equals(baseline.hashes().get(TEXT));
        var visible = new LinkedHashMap<String, Object>(existing.values());
        visible.put(ORIGINAL, current.original().text());
        visible.put(
                TEXT,
                current.draft() != null
                        ? current.draft().text()
                        : current.confirmed() != null
                                ? current.confirmed().text()
                                : current.machine() == null ? null : current.machine().text());
        var snapshot =
                new RecordExchangeAccess.Row(
                        existing.id(), existing.version(), existing.createdAt(), visible);
        return new Planned(
                input,
                receipt,
                snapshot,
                changed ? Map.of(TEXT, value) : Map.of(),
                Map.of(),
                List.of(),
                changed ? "DRAFT" : "SKIP");
    }

    private RecordExchangeAccess.Row saveTranslation(
            RecordExchangeAccess access, RecordExchangeReceipt document, Planned plan) {
        var baseline = baseline(plan.receipt());
        access.translation(
                plan.input().entity().alias(),
                plan.receipt().getParentId(),
                plan.existing().id(),
                baseline.field(),
                document.getLanguageTag(),
                new BusinessContentTranslationService.Change(
                        baseline.originalId(),
                        baseline.originalRevision(),
                        baseline.translationRevision(),
                        String.valueOf(plan.changes().get(TEXT)),
                        null));
        return plan.existing();
    }

    private int exportRow(
            RecordExchangeAccess access,
            RecordExchangeReceipt document,
            RecordExchangeDefinition.Entity entity,
            String parent,
            RecordExchangeAccess.Row row,
            Map<String, List<Map<String, Object>>> output) {
        var rows = output.computeIfAbsent(entity.alias(), ignored -> new ArrayList<>());
        var sources =
                entity.fields().stream().anyMatch(RecordExchangeDefinition.Field::content)
                        ? access.sources(entity.alias(), parent, row.id())
                        : null;
        if (TRANSLATIONS.equals(document.getPurpose())) {
            int count = 0;
            for (var field : entity.fields())
                if (field.content()) {
                    if (sources == null
                            || sources.fields().get(field.name()) == null
                            || sources.fields().get(field.name()).text() == null
                            || sources.fields().get(field.name()).text().isBlank()) continue;
                    var state =
                            access.translation(
                                    entity.alias(),
                                    parent,
                                    row.id(),
                                    field.name(),
                                    document.getLanguageTag(),
                                    null);
                    if (state.original() == null) continue;
                    String value =
                            state.draft() != null
                                    ? state.draft().text()
                                    : state.confirmed() != null
                                            ? state.confirmed().text()
                                            : state.machine() == null
                                                    ? null
                                                    : state.machine().text();
                    var hashes = new LinkedHashMap<String, String>();
                    hashes.put(ORIGINAL, hash(state.original().text()));
                    if (value != null) hashes.put(TEXT, hash(value));
                    var baseline =
                            new Baseline(
                                    hashes,
                                    Map.of(),
                                    Map.of(),
                                    field.name(),
                                    state.original().revisionId(),
                                    state.original().revision(),
                                    state.expectedRevision());
                    var receipt =
                            receipts.createRow(
                                    document, null, entity.alias(), parent, row, encode(baseline));
                    receipts.reviewed(receipt, "EXPORTED");
                    var values = new LinkedHashMap<String, Object>();
                    values.put(RELATE, entity.main() ? row.id() : parent);
                    values.put(ROW, receipt.getId());
                    values.put(FIELD, field.name());
                    values.put(ORIGINAL, state.original().text());
                    values.put(ORIGINAL_ID, baseline.originalId());
                    values.put(REVISION, baseline.originalRevision());
                    values.put(SOURCE, state.original().sourceLanguage());
                    values.put(TARGET, document.getLanguageTag());
                    values.put(TRANSLATION_REVISION, baseline.translationRevision());
                    values.put(TEXT, value);
                    rows.add(values);
                    count++;
                }
            return count;
        }
        var values = new LinkedHashMap<String, Object>();
        var hashes = new LinkedHashMap<String, String>();
        var refs = new LinkedHashMap<String, Object>();
        var revisions = new LinkedHashMap<String, Integer>();
        var reading =
                READING.equals(document.getPurpose())
                        ? access.reading(
                                entity.alias(), parent, row.id(), document.getLanguageTag())
                        : null;
        for (var field : entity.fields()) {
            if (!field.writable() && !field.companion() && reading == null) continue;
            Object raw = row.values().get(field.name());
            Object value =
                    field.reference()
                            ? access.referenceLabel(
                                    entity.alias(),
                                    parent,
                                    field.name(),
                                    raw,
                                    document.getLanguageTag(),
                                    row.values())
                            : access.outputValue(
                                    entity.alias(), field.name(), raw, document.getLanguageTag());
            if (field.reference() && raw != null) refs.put(field.name(), raw);
            if (reading == null) {
                values.put(field.name(), value);
                if (value != null)
                    hashes.put(field.name(), fingerprint(field, field.reference() ? value : raw));
                if (value != null)
                    hashes.put("cell:" + field.name(), cellHash(field, String.valueOf(value)));
            } else {
                var translation = reading.fields().get(field.name());
                values.put(reading(field.name()), translation == null ? value : translation.text());
                if (translation != null) {
                    values.put("__original_" + field.name(), translation.originalText());
                    values.put(
                            "__state_" + field.name(),
                            (translation.translation() == null
                                            ? "ORIGINAL"
                                            : translation.translation().kind())
                                    + (translation.humanReviewRequired()
                                            ? ":REVIEW_REQUIRED"
                                            : ""));
                }
            }
            if (sources != null && sources.fields().containsKey(field.name())) {
                var source = sources.fields().get(field.name());
                if (source.source() != null)
                    values.put(source(field.name()), source.source().sourceLanguage());
                if (source.expectedRevision() != null)
                    revisions.put(field.name(), source.expectedRevision());
            }
        }
        var receipt =
                receipts.createRow(
                        document,
                        null,
                        entity.alias(),
                        parent,
                        row,
                        encode(new Baseline(hashes, refs, revisions, null, null, null, null)));
        receipts.reviewed(receipt, "EXPORTED");
        values.put(ROW, receipt.getId());
        values.put(RELATE, entity.main() ? row.id() : parent);
        rows.add(values);
        return 1;
    }

    private byte[] retryWorkbook(
            RecordExchangeAccess access,
            RecordExchangeReceipt document,
            ParsedWorkbook workbook,
            List<Input> inputs,
            Map<Input, String> failures,
            Map<String, Saved> parents,
            Command command) {
        var values = new LinkedHashMap<String, List<Map<String, Object>>>();
        failures.forEach(
                (input, reason) -> {
                    var raw = new LinkedHashMap<String, Object>(input.raw());
                    if (DATA.equals(document.getPurpose())
                            && command != null
                            && command.sourceLanguage() != null) {
                        RecordExchangeReceipt receipt = null;
                        if (text(input.raw().get(ROW)) != null) {
                            try {
                                receipt =
                                        receipts.row(
                                                document,
                                                input.raw().get(ROW),
                                                input.entity().alias());
                            } catch (IllegalArgumentException invalidIdentity) {
                                // Preserve the original failed row; a bad identity grants no
                                // baseline.
                            }
                        }
                        var baseline = baseline(receipt);
                        for (var field : input.entity().fields()) {
                            if (field.content()
                                    && text(input.raw().get(field.name())) != null
                                    && !sameCell(baseline, field, input.raw().get(field.name())))
                                raw.put(source(field.name()), command.sourceLanguage());
                        }
                    }
                    raw.put(ERROR, reason);
                    raw.put(INPUT, input.key());
                    var context = parents.get(input.entity().alias() + ":" + input.key());
                    if (context != null && DATA.equals(document.getPurpose())) {
                        raw.clear();
                        raw.put(RELATE, input.raw().get(RELATE));
                        raw.put(ROW, context.receipt().getId());
                        raw.put(CONTEXT, true);
                        raw.put(ERROR, "仅关联上下文，不重复提交业务值 / Relation context only");
                    }
                    values.computeIfAbsent(input.entity().alias(), ignored -> new ArrayList<>())
                            .add(raw);
                });
        var retry =
                sheets(access, document.getPurpose(), document.getLanguageTag(), values).stream()
                        .map(
                                sheet ->
                                        new ExcelSheetPlan(
                                                sheet.sheetName(),
                                                sheet.entityAlias(),
                                                sheet.main(),
                                                sheet.columns().stream()
                                                        .map(
                                                                column ->
                                                                        new ExcelColumnPlan(
                                                                                column.fieldName(),
                                                                                column.title(),
                                                                                column.required(),
                                                                                ExcelValueType.TEXT,
                                                                                column
                                                                                        .dropdownOptions(),
                                                                                column
                                                                                        .titleSource()))
                                                        .toList(),
                                                sheet.rows()))
                        .toList();
        return writer.writeToBytes(
                new ExcelWorkbookPlan(
                        meta(
                                document,
                                workbook.meta() == null ? "UTC" : workbook.meta().timeZone(),
                                importLanguage(document, workbook, command)),
                        retry));
    }

    private ParsedWorkbook parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Upload an Excel file up to 8 MiB");
        var workbook = parser.parse(bytes);
        if (workbook.meta() != null
                && workbook.meta().protocolVersion() != null
                && !Set.of("1", "2").contains(workbook.meta().protocolVersion()))
            throw new IllegalArgumentException("Unsupported file protocol version");
        if (workbook.meta() != null
                && "2".equals(workbook.meta().protocolVersion())
                && text(workbook.meta().documentId()) == null)
            throw new IllegalArgumentException("文件身份缺失，请重新导出 / File identity is missing");
        if (workbook.sheets().size() > 50
                || workbook.sheets().stream().mapToInt(sheet -> sheet.rows().size()).sum()
                        > MAX_ROWS
                || workbook.sheets().stream().anyMatch(sheet -> sheet.columns().size() > 200))
            throw new IllegalArgumentException("File exceeds the bounded import size");
        return workbook;
    }

    private RecordExchangeReceipt document(
            RecordExchangeAccess access, ParsedWorkbook workbook, Command command, boolean create) {
        String module = access.definition().moduleAlias();
        if (workbook.meta() != null
                && workbook.meta().moduleAlias() != null
                && !module.equals(workbook.meta().moduleAlias()))
            throw new IllegalArgumentException("File belongs to a different module");
        String id = workbook.meta() == null ? null : text(workbook.meta().documentId());
        if (id != null
                && command != null
                && command.documentId() != null
                && !id.equals(command.documentId()))
            throw new IllegalArgumentException("Import document changed");
        if (id == null && command != null) id = command.documentId();
        if (id == null) {
            if (!create) throw new IllegalArgumentException("Upload and preview the file first");
            String language = workbook.meta() == null ? null : workbook.meta().language();
            if (language == null && command != null) language = command.resolutionLanguage();
            String resolvedLanguage = language;
            return tx(
                    () ->
                            receipts.document(
                                    module,
                                    DATA,
                                    resolvedLanguage == null
                                            ? null
                                            : LanguageCatalog.canonicalTag(resolvedLanguage)));
        }
        var document = receipts.find(module, id);
        if (!document.getId().equals(document.getDocumentId()) || document.getEntityAlias() != null)
            throw new IllegalArgumentException("Invalid file document identity");
        if (workbook.meta() != null
                && workbook.meta().purpose() != null
                && !document.getPurpose().equals(workbook.meta().purpose()))
            throw new IllegalArgumentException("File purpose conflicts with its verified identity");
        return document;
    }

    private String translationParent(RecordExchangeReceipt document, Input input) {
        if (!TRANSLATIONS.equals(document.getPurpose()) || input.entity().main()) return null;
        return receipts.row(document, input.raw().get(ROW), input.entity().alias()).getParentId();
    }

    private List<Input> inputs(
            RecordExchangeDefinition definition, ParsedWorkbook workbook, String purpose) {
        if (READING.equals(purpose))
            throw new IllegalArgumentException(
                    "阅读导出不能回导为业务原文 / Reading exports are not editable business data");
        var result = new ArrayList<Input>();
        var seen = new HashSet<String>();
        var parents = new HashSet<String>();
        var ordered =
                workbook.sheets().stream()
                        .sorted(
                                Comparator.comparing(
                                        sheet -> !definition.entity(sheet.entityAlias()).main()))
                        .toList();
        for (var sheet : ordered) {
            var entity = definition.entity(sheet.entityAlias());
            var allowed =
                    columns(entity, purpose).stream()
                            .map(ExcelColumnPlan::fieldName)
                            .collect(java.util.stream.Collectors.toSet());
            for (var column : sheet.columns())
                if (!allowed.contains(column.fieldName()))
                    throw new IllegalArgumentException(
                            "File contains a non-editable column: " + column.title());
            for (var row : sheet.rows()) {
                var raw = values(sheet, row);
                String group = text(raw.get(RELATE));
                if (group == null)
                    throw new IllegalArgumentException(
                            "每行必须填写关联标识 / Each row requires a relation identity");
                raw.put(RELATE, group);
                String key = text(raw.get(ROW));
                if (key == null) key = text(raw.get(INPUT));
                if (key == null)
                    key =
                            "new:"
                                    + entity.alias()
                                    + ":"
                                    + group
                                    + ":"
                                    + rowDigest(new Input(entity, sheet, raw, "")).substring(0, 24);
                if (key.length() > 512)
                    throw new IllegalArgumentException("Input row identity is too long");
                if (!seen.add(entity.alias() + ":" + key))
                    throw new IllegalArgumentException("Duplicate file row identity");
                if (entity.main() && !TRANSLATIONS.equals(purpose) && !parents.add(group))
                    throw new IllegalArgumentException("Duplicate parent relation identity");
                result.add(new Input(entity, sheet, raw, key));
            }
        }
        return result;
    }

    private Map<String, Object> convert(
            RecordExchangeAccess access,
            RecordExchangeReceipt document,
            RecordExchangeDefinition.Entity entity,
            Input input,
            ParsedWorkbook workbook,
            Command command,
            Baseline baseline) {
        var fields =
                entity.fields().stream()
                        .map(
                                field ->
                                        new DynamicImportPlan.FieldPlan(
                                                entity.alias(),
                                                field.name(),
                                                field.title(),
                                                field.type(),
                                                false,
                                                !field.companion(),
                                                field.companion()))
                        .toList();
        var plan =
                new DynamicImportPlan.SheetPlan(
                        input.sheet().sheetName(),
                        entity.alias(),
                        input.sheet().sheetName(),
                        entity.main(),
                        fields.getFirst().fieldName(),
                        ImportDuplicateStrategy.ERROR,
                        fields);
        var skipped = new HashSet<String>();
        for (var field : entity.fields())
            if (!field.companion()) {
                boolean unchanged = sameCell(baseline, field, input.raw().get(field.name()));
                if (field.type()
                        == net.ximatai.muyun.spring.dynamic.metadata.FieldType.ZONED_TIMESTAMP) {
                    String zone =
                            net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRules
                                    .zonedTimestampTimeZoneFieldName(field.name());
                    if (unchanged && sameCell(baseline, entity.field(zone), input.raw().get(zone))
                            || split(input.raw().get(CLEAR)).contains(field.name())
                                    && text(input.raw().get(field.name())) == null) {
                        skipped.add(field.name());
                        skipped.add(zone);
                    }
                } else if (unchanged) skipped.add(field.name());
            }
        int width =
                input.sheet().columns().stream()
                                .mapToInt(ParsedColumn::columnIndex)
                                .max()
                                .orElse(-1)
                        + 1;
        var row = new ArrayList<String>(Collections.nCopies(width, null));
        for (var column : input.sheet().columns()) {
            if (skipped.contains(column.fieldName())) continue;
            var field =
                    entity.fields().stream()
                            .filter(value -> value.name().equals(column.fieldName()))
                            .findFirst()
                            .orElse(null);
            String value = input.raw().get(column.fieldName());
            row.set(
                    column.columnIndex(),
                    field == null || value == null || value.isBlank()
                            ? value
                            : access.inputValue(
                                    entity.alias(),
                                    column.fieldName(),
                                    value,
                                    importLanguage(document, workbook, command)));
        }
        return new LinkedHashMap<>(
                converter
                        .convert(
                                plan,
                                input.sheet(),
                                row,
                                ImportTemporalContext.from(workbook),
                                true)
                        .convertedValues());
    }

    private static String importLanguage(
            RecordExchangeReceipt document, ParsedWorkbook workbook, Command command) {
        if (!DATA.equals(document.getPurpose())) return document.getLanguageTag();
        if (command != null && command.resolutionLanguage() != null)
            return command.resolutionLanguage();
        String language = workbook.meta() == null ? null : text(workbook.meta().language());
        return language == null
                ? document.getLanguageTag()
                : LanguageCatalog.canonicalTag(language);
    }

    private static boolean sameCell(
            Baseline baseline, RecordExchangeDefinition.Field field, String value) {
        return baseline.hashes().containsKey("cell:" + field.name())
                && Objects.equals(
                        baseline.hashes().get("cell:" + field.name()), cellHash(field, value));
    }

    private static String cellHash(RecordExchangeDefinition.Field field, String value) {
        return value == null
                ? null
                : hash(
                        !field.reference()
                                        && !field.companion()
                                        && (field.type()
                                                        == net.ximatai.muyun.spring.dynamic.metadata
                                                                .FieldType.STRING
                                                || field.type()
                                                        == net.ximatai.muyun.spring.dynamic.metadata
                                                                .FieldType.TEXT)
                                ? value
                                : value.trim());
    }

    private List<ExcelSheetPlan> sheets(
            RecordExchangeAccess access,
            String purpose,
            String language,
            Map<String, List<Map<String, Object>>> rows) {
        var result = new ArrayList<ExcelSheetPlan>();
        for (var entity : access.definition().entities()) {
            var columns =
                    columns(entity, purpose).stream()
                            .map(
                                    column ->
                                            new ExcelColumnPlan(
                                                    column.fieldName(),
                                                    column.title(),
                                                    entity.fields().stream()
                                                            .anyMatch(
                                                                    field ->
                                                                            field.name()
                                                                                            .equals(
                                                                                                    column
                                                                                                            .fieldName())
                                                                                    && field
                                                                                            .required()),
                                                    column.valueType(),
                                                    DATA.equals(purpose)
                                                            ? access.options(
                                                                    entity.alias(),
                                                                    column.fieldName(),
                                                                    language)
                                                            : List.of(),
                                                    column.titleSource()))
                            .toList();
            result.add(
                    sheet(
                            entity,
                            columns,
                            rows.getOrDefault(entity.alias(), List.of()),
                            result.size()));
        }
        return result;
    }

    private Baseline baseline(RecordExchangeReceipt receipt) {
        if (receipt == null || receipt.getBaselineJson() == null) return Baseline.empty();
        try {
            return json.readValue(receipt.getBaselineJson(), Baseline.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Cannot read the persisted file baseline", invalid);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("Cannot encode file identity", invalid);
        }
    }

    private static String generatedRowId(RecordExchangeReceipt document, Input input) {
        return hash(document.getId() + ":" + input.entity().alias() + ":" + input.key())
                .substring(0, 32);
    }

    private String rowDigest(Input input) {
        var values = new TreeMap<>(input.raw());
        values.remove(ERROR);
        values.remove(ROW);
        values.remove(INPUT);
        values.remove(CONTEXT);
        values.entrySet().removeIf(entry -> text(entry.getValue()) == null);
        return hash(encode(values));
    }

    private static String fingerprint(RecordExchangeDefinition.Field field, Object value) {
        if (value == null) return null;
        if (value instanceof Number number)
            return hash(
                    new java.math.BigDecimal(number.toString())
                            .stripTrailingZeros()
                            .toPlainString());
        return cellHash(field, String.valueOf(value));
    }

    private String digest(byte[] bytes, Command command, String documentId) {
        var intent = new TreeMap<String, Object>();
        intent.put("document", documentId);
        intent.put("file", Base64.getEncoder().encodeToString(bytes));
        intent.put("sheets", command == null ? List.of() : command.sheets());
        intent.put("confirmations", command == null ? List.of() : command.confirmations());
        intent.put("sourceLanguage", command == null ? null : command.sourceLanguage());
        intent.put("resolutionLanguage", command == null ? null : command.resolutionLanguage());
        return hash(encode(intent));
    }

    private static String hash(String value) {
        return RecordSaveReceiptService.digest(value);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nonblank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Set<String> split(String value) {
        return value == null
                ? Set.of()
                : new LinkedHashSet<>(
                        Arrays.stream(value.split(","))
                                .map(String::trim)
                                .filter(item -> !item.isBlank())
                                .toList());
    }

    private static PreviewRow view(RecordExchangeAccess access, Planned plan, String parent, String language) {
        Map<String, Object> before = plan.existing() == null ? Map.of() : plan.existing().values();
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.putAll(plan.changes());
        Map<String, ReferenceDisplay> references = new LinkedHashMap<>();
        for (var field : plan.input().entity().fields()) {
            if (!field.reference() || !plan.changes().containsKey(field.name())) continue;
            references.put(field.name(), new ReferenceDisplay(
                    previewReferenceLabel(access, plan, parent, field.name(), before.get(field.name()), language, before),
                    previewReferenceLabel(access, plan, parent, field.name(), after.get(field.name()), language, after)));
        }
        return new PreviewRow(
                plan.input().key(),
                plan.input().entity().alias(),
                plan.input().raw().get(RELATE),
                plan.operation(),
                plan.existing() == null ? null : plan.existing().id(),
                plan.existing() == null ? Map.of() : plan.existing().values(),
                plan.changes(),
                plan.languages(),
                plan.choices(),
                null, Map.copyOf(references));
    }

    private static String previewReferenceLabel(RecordExchangeAccess access, Planned plan, String parent,
                                                String field, Object ids, String language, Map<String, Object> values) {
        if (ids == null) return null;
        try {
            return access.referenceLabel(plan.input().entity().alias(), parent, field, ids, language, values);
        } catch (net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException
                 | IllegalArgumentException unavailable) {
            // Optional presentation never discloses a protected name or changes the verified identity.
            return null;
        }
    }

    private static PreviewRow problem(Input input, RuntimeException failure) {
        return new PreviewRow(
                input.key(),
                input.entity().alias(),
                input.raw().get(RELATE),
                "ERROR",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                message(failure));
    }

    private static boolean collectable(RuntimeException failure) {
        return failure instanceof IllegalArgumentException
                || failure instanceof OptimisticLockException
                || failure instanceof PlatformException platform
                        && platform.httpStatus() >= 400
                        && platform.httpStatus() < 500;
    }

    private static String message(RuntimeException failure) {
        if (failure instanceof IllegalArgumentException
                || failure instanceof OptimisticLockException) return failure.getMessage();
        if (failure instanceof PlatformException platform
                && Set.of(400, 422).contains(platform.httpStatus())
                && net.ximatai.muyun.spring.common.exception.PlatformErrorCodes.VALIDATION_FAILED
                        .equals(platform.code())) {
            String fields =
                    platform.targets().stream()
                            .filter(
                                    target ->
                                            "field".equals(target.kind())
                                                    && target.fieldName() != null)
                            .map(target -> target.fieldName())
                            .distinct()
                            .collect(java.util.stream.Collectors.joining(", "));
            return fields.isBlank() ? platform.getMessage() : fields + ": " + platform.getMessage();
        }
        return "Current permissions or business state reject this row / 当前权限或业务状态不允许此操作";
    }

    private <T> T tx(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
