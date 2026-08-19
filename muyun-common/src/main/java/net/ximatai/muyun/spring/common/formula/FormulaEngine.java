package net.ximatai.muyun.spring.common.formula;

import net.ximatai.muyun.spring.common.formula.FormulaAst.AssignNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.AstNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.BinaryNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.FieldNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.FuncNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.UnaryNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.ValueNode;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData.RowValue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class FormulaEngine {
    private static final DateTimeFormatter LEGACY_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern UTC_Z_SECOND_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    private static final Pattern DB_UTC_SECOND_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

    private final FormulaTokenizer tokenizer = new FormulaTokenizer();
    private final Clock clock;

    public FormulaEngine() {
        this(Clock.systemUTC());
    }

    public FormulaEngine(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Object evaluateValue(String expression, FormulaEvaluationContext data) {
        FormulaExpressionSupport.ParsedExpression parsed = parse("expression", expression);
        if (parsed == null) {
            return null;
        }
        return eval(parsed.ast(), data, FormulaEvaluationScope.main()).value();
    }

    public boolean evaluateBoolean(String expression, FormulaEvaluationContext data) {
        return toBoolean(evaluateValue(expression, data));
    }

    public FormulaRuntimeReport apply(List<FormulaRule> rules, FormulaEvaluationContext data) {
        return execute(rules, data).report();
    }

    public FormulaExecutionResult execute(List<FormulaRule> rules, FormulaEvaluationContext data) {
        FormulaExecutionResult result = new FormulaExecutionResult();
        if (rules == null || rules.isEmpty()) {
            return result;
        }
        for (FormulaRule rule : rules) {
            if (rule == null || !rule.enabled()) {
                continue;
            }
            int errorCountBeforeRule = result.report().errors().size();
            try {
                FormulaExpressionSupport.ParsedExpression parsed = parse(rule.id(), rule.expression());
                if (parsed == null) {
                    continue;
                }
                applyRule(rule, parsed, data, result);
                if (rule.stopOnError() && result.report().errors().size() > errorCountBeforeRule) {
                    break;
                }
            } catch (FormulaEvaluationException ex) {
                result.report().error(rule, ex.code(), ex.fieldPath(), ex.position(), rule.expression(), ex.getMessage());
                if (rule.stopOnError()) {
                    break;
                }
            } catch (Exception ex) {
                result.report().error(rule, "FORMULA_EVALUATE_ERROR", null, null, rule.expression(), ex.getMessage());
                if (rule.stopOnError()) {
                    break;
                }
            }
        }
        return result;
    }

    public FormulaExpressionSupport.ParsedExpression parse(String ruleId, String expression) {
        return FormulaExpressionSupport.parse(ruleId, expression, tokenizer);
    }

    public Set<String> referencedFields(String expression) {
        FormulaExpressionSupport.ParsedExpression parsed = parse("expression", expression);
        if (parsed == null) {
            return Set.of();
        }
        return FormulaExpressionSupport.referencedFields(parsed.ast());
    }

    /** Returns every field written by an assignment anywhere in the expression. */
    public Set<String> assignedFields(String expression) {
        FormulaExpressionSupport.ParsedExpression parsed = parse("expression", expression);
        if (parsed == null) {
            return Set.of();
        }
        return FormulaExpressionSupport.assignedFields(parsed.ast());
    }

    /**
     * Compiles a FormulaEngine expression to the only profile that may execute in a Web form.
     * The returned AST is signed by the server descriptor; Web clients must not parse {@code expression} again.
     */
    public FormulaProgram compileWebUiProgram(String expression) {
        try {
            return FormulaWebUiProfile.compile(parse("web-ui", expression));
        } catch (FormulaEvaluationException exception) {
            if ("FORMULA_WEB_UI_UNSUPPORTED".equals(exception.code())) {
                throw exception;
            }
            throw new FormulaEvaluationException("FORMULA_WEB_UI_UNSUPPORTED",
                    "formula is not supported by WEB_UI profile: " + expression);
        }
    }

    /**
     * Compiles one deterministic main-record assignment for a browser-local form calculation.
     * This shares FormulaEngine parsing and AST semantics with server execution, but deliberately
     * does not authorize persistence, scheduling, or user-value overwrite policy.
     */
    public FormulaProgram compileFormComputeProgram(String expression) {
        try {
            return FormulaFormComputeProfile.compile(parse("form-compute", expression));
        } catch (FormulaEvaluationException exception) {
            if ("FORMULA_FORM_COMPUTE_UNSUPPORTED".equals(exception.code())) {
                throw exception;
            }
            throw new FormulaEvaluationException("FORMULA_FORM_COMPUTE_UNSUPPORTED",
                    "formula is not supported by FORM_COMPUTE profile: " + expression);
        }
    }

    public boolean containsAssignment(String expression) {
        FormulaExpressionSupport.ParsedExpression parsed = parse("expression", expression);
        return parsed != null && FormulaExpressionSupport.containsAssignment(parsed.ast());
    }

    public void validateTargetFieldExpressionScope(String targetField, String expression) {
        FormulaFieldPath target = FormulaFieldPath.parse(targetField);
        if (target.tableKey() == null) {
            return;
        }
        FormulaExpressionSupport.ParsedExpression parsed = parse("expression", expression);
        if (parsed == null) {
            return;
        }
        for (String fieldPath : directChildFieldReferences(parsed.ast(), false)) {
            FormulaFieldPath reference = FormulaFieldPath.parse(fieldPath);
            if (reference.tableKey() != null && !Objects.equals(reference.tableKey(), target.tableKey())) {
                throw new FormulaEvaluationException(
                        "FORMULA_TARGET_SCOPE_ERROR",
                        reference.dataIndex(),
                        "child target formula cannot directly read another child table: " + reference.dataIndex()
                );
            }
        }
    }

    private void applyRule(
            FormulaRule rule,
            FormulaExpressionSupport.ParsedExpression parsed,
            FormulaEvaluationContext data,
            FormulaExecutionResult result
    ) {
        if (rule.kind() == FormulaRuleKind.CALCULATION) {
            applyCalculationRule(rule, parsed, data, result);
            return;
        }
        if (FormulaExpressionSupport.containsAssignment(parsed.ast())) {
            result.report().error(rule, "FORMULA_ASSIGNMENT_NOT_ALLOWED", rule.targetField(), null, parsed.expression(),
                    "formula assignment is only allowed in calculation rules");
            return;
        }
        boolean passed = toBoolean(eval(parsed.ast(), data, FormulaEvaluationScope.main()).value());
        result.decide(rule, passed);
        if (!passed) {
            result.report().add(rule, "FORMULA_RULE_NOT_MATCHED", rule.targetField(), null, parsed.expression(),
                    rule.messageTemplate() == null ? "formula condition is not matched" : rule.messageTemplate());
        }
    }

    private void applyCalculationRule(
            FormulaRule rule,
            FormulaExpressionSupport.ParsedExpression parsed,
            FormulaEvaluationContext data,
            FormulaExecutionResult result
    ) {
        FormulaEvaluationSession session = data.beginSession();
        if (rule.targetField() != null && !FormulaExpressionSupport.containsAssignment(parsed.ast())) {
            FormulaFieldPath targetField = FormulaFieldPath.parse(rule.targetField());
            if (targetField.tableKey() == null) {
                EvalResult evalResult = eval(parsed.ast(), session, FormulaEvaluationScope.main());
                session.set(
                        targetField,
                        normalizeCalculatedValue(evalResult.value()),
                        FormulaEvaluationScope.main()
                );
            } else {
                for (Object row : session.rows(targetField.tableKey())) {
                    FormulaEvaluationScope rowScope = FormulaEvaluationScope.row(targetField.tableKey(), row);
                    EvalResult evalResult = eval(parsed.ast(), session, rowScope);
                    session.set(targetField, normalizeCalculatedValue(evalResult.value()), rowScope);
                }
            }
            commitSession(session, result);
            return;
        }
        if (!FormulaExpressionSupport.containsAssignment(parsed.ast())) {
            result.report().error(rule, "FORMULA_ASSIGNMENT_REQUIRED", rule.targetField(), null, parsed.expression(),
                    "calculation formula requires assignment or target field");
            return;
        }
        try {
            eval(parsed.ast(), session, FormulaEvaluationScope.main());
            commitSession(session, result);
        } catch (RuntimeException ex) {
            session.rollback();
            throw ex;
        }
    }

    private void commitSession(FormulaEvaluationSession session, FormulaExecutionResult result) {
        session.commit().stream()
                .filter(FormulaFieldWriteResult::changed)
                .map(FormulaFieldWriteResult::fieldPath)
                .forEach(result::changed);
    }

    private EvalResult eval(AstNode node, FormulaEvaluationContext data, FormulaEvaluationScope context) {
        if (node == null) {
            return EvalResult.of(null, false);
        }
        return switch (node.type) {
            case VALUE -> EvalResult.of(((ValueNode) node).value, false);
            case FIELD -> EvalResult.of(data.get(FormulaFieldPath.parse(((FieldNode) node).dataIndex), context), false);
            case UNARY -> evalUnary((UnaryNode) node, data, context);
            case BINARY -> evalBinary((BinaryNode) node, data, context);
            case ASSIGN -> evalAssign((AssignNode) node, data, context);
            case FUNC -> evalFunction((FuncNode) node, data, context);
            default -> EvalResult.of(null, false);
        };
    }

    private EvalResult evalUnary(UnaryNode node, FormulaEvaluationContext data, FormulaEvaluationScope context) {
        EvalResult arg = eval(node.arg, data, context);
        Object value = switch (node.op) {
            case "!" -> !toBoolean(arg.value());
            case "-" -> -toNumber(arg.value());
            case "+" -> toNumber(arg.value());
            default -> arg.value();
        };
        return arg.withValue(value);
    }

    private EvalResult evalBinary(BinaryNode node, FormulaEvaluationContext data, FormulaEvaluationScope context) {
        EvalResult left = eval(node.left, data, context);
        if ("&&".equals(node.op) && !toBoolean(left.value())) {
            return left.withValue(false);
        }
        if ("||".equals(node.op) && toBoolean(left.value())) {
            return left.withValue(true);
        }
        EvalResult right = eval(node.right, data, context);
        Object value = switch (node.op) {
            case "+" -> {
                if (left.value() instanceof String || right.value() instanceof String) {
                    yield String.valueOf(left.value() == null ? "" : left.value())
                            + String.valueOf(right.value() == null ? "" : right.value());
                }
                yield toNumber(left.value()) + toNumber(right.value());
            }
            case "-" -> toNumber(left.value()) - toNumber(right.value());
            case "*" -> toNumber(left.value()) * toNumber(right.value());
            case "/" -> {
                double divisor = toNumber(right.value());
                yield divisor == 0d ? 0d : toNumber(left.value()) / divisor;
            }
            case "%" -> {
                double divisor = toNumber(right.value());
                yield divisor == 0d ? 0d : toNumber(left.value()) % divisor;
            }
            case ">" -> compare(left.value(), right.value()) > 0;
            case "<" -> compare(left.value(), right.value()) < 0;
            case ">=" -> compare(left.value(), right.value()) >= 0;
            case "<=" -> compare(left.value(), right.value()) <= 0;
            case "==" -> equalsLoose(left.value(), right.value());
            case "!=" -> !equalsLoose(left.value(), right.value());
            case "&&" -> toBoolean(left.value()) && toBoolean(right.value());
            case "||" -> toBoolean(left.value()) || toBoolean(right.value());
            default -> null;
        };
        return EvalResult.of(value, left, right);
    }

    private EvalResult evalAssign(AssignNode node, FormulaEvaluationContext data, FormulaEvaluationScope context) {
        if (!(node.left instanceof FieldNode fieldNode)) {
            return eval(node.right, data, context);
        }
        EvalResult right = eval(node.right, data, context);
        FormulaFieldWriteResult writeResult = data.set(
                FormulaFieldPath.parse(fieldNode.dataIndex),
                normalizeCalculatedValue(right.value()),
                context
        );
        return right.withWriteResult(writeResult);
    }

    private EvalResult evalFunction(FuncNode node, FormulaEvaluationContext data, FormulaEvaluationScope context) {
        String name = FormulaFunctions.normalize(node.name);
        if (FormulaFunctions.isAggregate(name)) {
            return evalAggregate(node, data, context, name);
        }
        if ("IF".equals(name)) {
            return evalIf(node, data, context);
        }
        List<EvalResult> results = new ArrayList<>();
        for (AstNode arg : node.args) {
            results.add(eval(arg, data, context));
        }
        List<Object> args = results.stream().map(EvalResult::value).toList();
        return EvalResult.of(evalScalar(name, args), results);
    }

    private EvalResult evalIf(FuncNode node, FormulaEvaluationContext data, FormulaEvaluationScope context) {
        EvalResult condition = node.args.isEmpty() ? EvalResult.of(false, false) : eval(node.args.get(0), data, context);
        AstNode branch = toBoolean(condition.value())
                ? (node.args.size() > 1 ? node.args.get(1) : null)
                : (node.args.size() > 2 ? node.args.get(2) : null);
        EvalResult result = eval(branch, data, context);
        return EvalResult.of(result.value(), condition, result);
    }

    private EvalResult evalAggregate(FuncNode node, FormulaEvaluationContext data, FormulaEvaluationScope context, String functionName) {
        AggregateArgs aggregateArgs = parseAggregateArgs(node.args);
        List<String> fieldRefs = new ArrayList<>();
        for (AstNode arg : aggregateArgs.valueArgs()) {
            collectFieldRefs(arg, fieldRefs);
        }
        collectFieldRefs(aggregateArgs.condition(), fieldRefs);
        Set<String> tableKeys = fieldRefs.stream()
                .map(FormulaFieldPath::parse)
                .filter(ref -> ref.tableKey() != null)
                .map(FormulaFieldPath::tableKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (tableKeys.size() > 1) {
            throw new FormulaEvaluationException(
                    "FORMULA_AGGREGATE_SCOPE_ERROR",
                    "aggregate formula cannot mix child tables: " + tableKeys
            );
        }
        String tableKey = tableKeys.stream().findFirst().orElse(null);
        List<?> rows = tableKey == null
                ? List.of(context.row() == null ? new RowValue(new java.util.LinkedHashMap<>()) : context.row())
                : data.rows(tableKey);
        List<Object> bucket = new ArrayList<>();
        boolean changed = false;
        for (Object row : rows) {
            if (aggregateArgs.excludeCurrentRow()
                    && context.row() != null
                    && Objects.equals(context.tableKey(), tableKey)
                    && row == context.row()) {
                continue;
            }
            FormulaEvaluationScope rowContext = tableKey == null ? context : FormulaEvaluationScope.row(tableKey, row);
            if (aggregateArgs.condition() != null) {
                EvalResult condition = eval(aggregateArgs.condition(), data, rowContext);
                changed = changed || condition.changed();
                if (!toBoolean(condition.value())) {
                    continue;
                }
            }
            for (AstNode arg : aggregateArgs.valueArgs()) {
                EvalResult value = eval(arg, data, rowContext);
                bucket.add(value.value());
                changed = changed || value.changed();
            }
        }
        Object value = switch (functionName) {
            case "SUM" -> bucket.stream().mapToDouble(this::toNumber).sum();
            case "AVG" -> bucket.isEmpty() ? 0d : bucket.stream().mapToDouble(this::toNumber).average().orElse(0d);
            case "COUNT" -> bucket.stream().filter(v -> v != null && !"".equals(v)).count();
            case "MAX" -> bucket.isEmpty() ? 0d : bucket.stream().mapToDouble(this::toNumber).max().orElse(0d);
            case "MIN" -> bucket.isEmpty() ? 0d : bucket.stream().mapToDouble(this::toNumber).min().orElse(0d);
            case "GET_FIRST_OR_DEFAULT_VALUE" -> bucket.isEmpty() || bucket.getFirst() == null ? "" : bucket.getFirst();
            default -> null;
        };
        return EvalResult.of(value, changed);
    }

    private AggregateArgs parseAggregateArgs(List<AstNode> args) {
        List<AstNode> valueArgs = new ArrayList<>();
        AstNode condition = null;
        boolean excludeCurrentRow = false;
        if (args == null) {
            return new AggregateArgs(valueArgs, condition, excludeCurrentRow);
        }
        for (AstNode arg : args) {
            String option = aggregateOption(arg);
            if ("EXCLUDE_CURRENT_ROW".equals(option)) {
                excludeCurrentRow = true;
                continue;
            }
            if ("INCLUDE_CURRENT_ROW".equals(option)) {
                continue;
            }
            if (arg instanceof FuncNode funcNode && isAggregateConditionFunction(funcNode.name)) {
                if (condition != null || funcNode.args.size() != 1) {
                    throw new FormulaEvaluationException(
                            "FORMULA_AGGREGATE_CONDITION_INVALID",
                            "aggregate condition requires exactly one WHERE/FILTER argument"
                    );
                }
                condition = funcNode.args.getFirst();
                continue;
            }
            if (arg != null && arg.type != FormulaAst.NodeType.EMPTY) {
                valueArgs.add(arg);
            }
        }
        return new AggregateArgs(valueArgs, condition, excludeCurrentRow);
    }

    private String aggregateOption(AstNode arg) {
        if (!(arg instanceof ValueNode valueNode) || valueNode.value == null) {
            return null;
        }
        String value = String.valueOf(valueNode.value).trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "INCLUDE_CURRENT_ROW", "EXCLUDE_CURRENT_ROW" -> value;
            default -> null;
        };
    }

    private boolean isAggregateConditionFunction(String name) {
        String normalized = FormulaFunctions.normalize(name);
        return "WHERE".equals(normalized) || "FILTER".equals(normalized);
    }

    private record AggregateArgs(List<AstNode> valueArgs, AstNode condition, boolean excludeCurrentRow) {
    }

    private Set<String> directChildFieldReferences(AstNode node, boolean inAggregate) {
        Set<String> fields = new LinkedHashSet<>();
        collectDirectChildFieldReferences(node, inAggregate, fields);
        return fields;
    }

    private void collectDirectChildFieldReferences(AstNode node, boolean inAggregate, Set<String> fields) {
        if (node == null) {
            return;
        }
        if (node instanceof FieldNode fieldNode) {
            FormulaFieldPath fieldPath = FormulaFieldPath.parse(fieldNode.dataIndex);
            if (!inAggregate && fieldPath.tableKey() != null) {
                fields.add(fieldPath.dataIndex());
            }
            return;
        }
        if (node instanceof AssignNode assignNode) {
            collectDirectChildFieldReferences(assignNode.left, inAggregate, fields);
            collectDirectChildFieldReferences(assignNode.right, inAggregate, fields);
            return;
        }
        if (node instanceof UnaryNode unaryNode) {
            collectDirectChildFieldReferences(unaryNode.arg, inAggregate, fields);
            return;
        }
        if (node instanceof BinaryNode binaryNode) {
            collectDirectChildFieldReferences(binaryNode.left, inAggregate, fields);
            collectDirectChildFieldReferences(binaryNode.right, inAggregate, fields);
            return;
        }
        if (node instanceof FuncNode funcNode) {
            boolean childInAggregate = inAggregate || FormulaFunctions.isAggregate(funcNode.name);
            funcNode.args.forEach(arg -> collectDirectChildFieldReferences(arg, childInAggregate, fields));
        }
    }

    private Object evalScalar(String name, List<Object> args) {
        return switch (name) {
            case "ABS" -> Math.abs(toNumber(arg(args, 0)));
            case "ROUND" -> round(toNumber(arg(args, 0)), (int) Math.max(0, toNumber(arg(args, 1))));
            case "LEN" -> String.valueOf(argNullable(args, 0)).length();
            case "CONCAT" -> args.stream().map(value -> String.valueOf(value == null ? "" : value)).reduce("", String::concat);
            case "SUBSTR" -> substr(argNullable(args, 0), toNumber(arg(args, 1)), toNumber(arg(args, 2)));
            case "UPPER" -> String.valueOf(argNullable(args, 0)).toUpperCase(Locale.ROOT);
            case "LOWER" -> String.valueOf(argNullable(args, 0)).toLowerCase(Locale.ROOT);
            case "NOW" -> DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock).truncatedTo(ChronoUnit.SECONDS));
            case "TODAY" -> LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).toLocalDate().format(DATE_FORMATTER);
            case "YEAR" -> {
                LocalDateTime dateTime = toDateTime(arg(args, 0));
                yield dateTime == null ? 0 : dateTime.getYear();
            }
            case "MONTH" -> {
                LocalDateTime dateTime = toDateTime(arg(args, 0));
                yield dateTime == null ? 0 : dateTime.getMonthValue();
            }
            case "DAY" -> {
                LocalDateTime dateTime = toDateTime(arg(args, 0));
                yield dateTime == null ? 0 : dateTime.getDayOfMonth();
            }
            case "DATE_ADD" -> dateAdd(arg(args, 0), requireInteger(arg(args, 1), "DATE_ADD.days"));
            case "DATE_SUB" -> dateAdd(arg(args, 0), -requireInteger(arg(args, 1), "DATE_SUB.days"));
            case "DATETIME_ADD" -> dateTimeAdd(arg(args, 0), requireInteger(arg(args, 1), "DATETIME_ADD.amount"), requireDateTimeUnit(arg(args, 2), "DATETIME_ADD.unit"));
            case "DATETIME_SUB" -> dateTimeAdd(arg(args, 0), -requireInteger(arg(args, 1), "DATETIME_SUB.amount"), requireDateTimeUnit(arg(args, 2), "DATETIME_SUB.unit"));
            case "DATE_DIFF_DAYS" -> diffDateTime(arg(args, 1), arg(args, 0), 24d * 60 * 60 * 1000);
            case "DATE_DIFF_HOURS" -> diffDateTime(arg(args, 1), arg(args, 0), 60d * 60 * 1000);
            case "ISNULL" -> {
                Object value = argNullable(args, 0);
                yield value == null || "".equals(value);
            }
            case "PRESENT" -> {
                Object value = argNullable(args, 0);
                yield value != null && !"".equals(value);
            }
            case "IN" -> {
                Object candidate = arg(args, 0);
                yield args.stream().skip(1).anyMatch(value -> equalsLoose(candidate, value));
            }
            case "WHERE", "FILTER" -> throw new FormulaEvaluationException(
                    "FORMULA_AGGREGATE_CONDITION_ONLY",
                    name + " only supports aggregate condition"
            );
            default -> throw new FormulaEvaluationException(
                    "FORMULA_UNKNOWN_FUNCTION",
                    "unknown formula function: " + name
            );
        };
    }

    private void collectFieldRefs(AstNode node, List<String> refs) {
        if (node == null) {
            return;
        }
        if (node instanceof FieldNode fieldNode) {
            refs.add(fieldNode.dataIndex);
        } else if (node instanceof AssignNode assignNode) {
            collectFieldRefs(assignNode.left, refs);
            collectFieldRefs(assignNode.right, refs);
        } else if (node instanceof BinaryNode binaryNode) {
            collectFieldRefs(binaryNode.left, refs);
            collectFieldRefs(binaryNode.right, refs);
        } else if (node instanceof UnaryNode unaryNode) {
            collectFieldRefs(unaryNode.arg, refs);
        } else if (node instanceof FuncNode funcNode) {
            funcNode.args.forEach(arg -> collectFieldRefs(arg, refs));
        }
    }

    private Object arg(List<Object> args, int index) {
        return index >= 0 && index < args.size() ? args.get(index) : null;
    }

    private Object argNullable(List<Object> args, int index) {
        Object value = arg(args, index);
        return value == null ? "" : value;
    }

    private double round(double value, int precision) {
        double factor = Math.pow(10, precision);
        return Math.round(value * factor) / factor;
    }

    private String substr(Object value, double startValue, double lengthValue) {
        String text = String.valueOf(value);
        int start = Math.max(0, (int) startValue);
        int length = Math.max(0, (int) lengthValue);
        if (start >= text.length()) {
            return "";
        }
        if (length <= 0) {
            return text.substring(start);
        }
        return text.substring(start, Math.min(text.length(), start + length));
    }

    private String dateAdd(Object value, long days) {
        if (isBlank(value)) {
            return "";
        }
        return requireDateOnly(value, "DATE_ADD.value").plusDays(days).format(DATE_FORMATTER);
    }

    private String dateTimeAdd(Object value, long amount, ChronoUnit unit) {
        if (isBlank(value)) {
            return "";
        }
        return DateTimeFormatter.ISO_INSTANT.format(requireUtcSecondInstant(value, "DATETIME_ADD.value").plus(amount, unit));
    }

    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return false;
        }
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        try {
            return Double.parseDouble(text) != 0d;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private double toNumber(Object value) {
        if (value == null) {
            return 0d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            double numeric = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(numeric) ? numeric : 0d;
        } catch (NumberFormatException ignored) {
            return 0d;
        }
    }

    private int compare(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        Optional<Instant> leftInstant = tryParseUtcSecondInstant(left);
        Optional<Instant> rightInstant = tryParseUtcSecondInstant(right);
        if (leftInstant.isPresent() && rightInstant.isPresent()) {
            return leftInstant.get().compareTo(rightInstant.get());
        }
        if (isNumberLike(left) || isNumberLike(right)) {
            return Double.compare(toNumber(left), toNumber(right));
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private boolean equalsLoose(Object left, Object right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        Optional<Instant> leftInstant = tryParseUtcSecondInstant(left);
        Optional<Instant> rightInstant = tryParseUtcSecondInstant(right);
        if (leftInstant.isPresent() && rightInstant.isPresent()) {
            return leftInstant.get().equals(rightInstant.get());
        }
        if (isNumberLike(left) || isNumberLike(right)) {
            return Double.compare(toNumber(left), toNumber(right)) == 0;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private Object normalizeCalculatedValue(Object value) {
        if (value instanceof Double d && Double.isNaN(d)) {
            return 0d;
        }
        if (value instanceof Float f && Float.isNaN(f)) {
            return 0f;
        }
        return value;
    }

    private boolean isBlank(Object value) {
        return value == null || value instanceof CharSequence text && text.toString().isBlank();
    }

    private LocalDate requireDateOnly(Object value, String fieldName) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (!(value instanceof CharSequence)) {
            throw new FormulaEvaluationException(
                    "FORMULA_DATE_FORMAT_ERROR",
                    fieldName,
                    "field [%s] must be yyyy-MM-dd".formatted(fieldName)
            );
        }
        String text = value.toString().trim();
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new FormulaEvaluationException(
                    "FORMULA_DATE_FORMAT_ERROR",
                    fieldName,
                    "field [%s] must be yyyy-MM-dd".formatted(fieldName)
            );
        }
        try {
            return LocalDate.parse(text, DATE_FORMATTER);
        } catch (DateTimeParseException ex) {
            throw new FormulaEvaluationException(
                    "FORMULA_DATE_FORMAT_ERROR",
                    fieldName,
                    "field [%s] must be yyyy-MM-dd".formatted(fieldName)
            );
        }
    }

    private Instant requireUtcSecondInstant(Object value, String fieldName) {
        return tryParseUtcSecondInstant(value)
                .orElseThrow(() -> new FormulaEvaluationException(
                        "FORMULA_DATETIME_FORMAT_ERROR",
                        fieldName,
                        "field [%s] must be UTC second instant".formatted(fieldName)
                ));
    }

    private Optional<Instant> tryParseUtcSecondInstant(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Instant instant) {
            return instant.getNano() == 0 ? Optional.of(instant) : Optional.empty();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.getNano() == 0 ? Optional.of(dateTime.toInstant(ZoneOffset.UTC)) : Optional.empty();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.getNano() == 0 && ZoneOffset.UTC.equals(offsetDateTime.getOffset())
                    ? Optional.of(offsetDateTime.toInstant())
                    : Optional.empty();
        }
        if (!(value instanceof CharSequence)) {
            return Optional.empty();
        }
        String text = value.toString().trim();
        try {
            if (UTC_Z_SECOND_PATTERN.matcher(text).matches()) {
                return Optional.of(Instant.parse(text));
            }
            if (DB_UTC_SECOND_PATTERN.matcher(text).matches()) {
                return Optional.of(LocalDateTime.parse(text, LEGACY_DATE_TIME_FORMATTER).toInstant(ZoneOffset.UTC));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private long requireInteger(Object value, String fieldName) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric) && numeric == Math.rint(numeric)) {
                return (long) numeric;
            }
        }
        if (value instanceof CharSequence text && text.toString().trim().matches("[-+]?\\d+")) {
            return Long.parseLong(text.toString().trim());
        }
        throw new FormulaEvaluationException(
                "FORMULA_TYPE_MISMATCH",
                fieldName,
                "%s must be integer".formatted(fieldName)
        );
    }

    private ChronoUnit requireDateTimeUnit(Object value, String fieldName) {
        if (!(value instanceof CharSequence)) {
            throw new FormulaEvaluationException(
                    "FORMULA_TYPE_MISMATCH",
                    fieldName,
                    "%s only supports DAY/HOUR/MINUTE/SECOND".formatted(fieldName)
            );
        }
        return switch (value.toString().trim().toUpperCase(Locale.ROOT)) {
            case "DAY" -> ChronoUnit.DAYS;
            case "HOUR" -> ChronoUnit.HOURS;
            case "MINUTE" -> ChronoUnit.MINUTES;
            case "SECOND" -> ChronoUnit.SECONDS;
            default -> throw new FormulaEvaluationException(
                    "FORMULA_TYPE_MISMATCH",
                    fieldName,
                    "%s only supports DAY/HOUR/MINUTE/SECOND".formatted(fieldName)
            );
        };
    }

    private boolean isNumberLike(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value == null) {
            return false;
        }
        try {
            Double.parseDouble(String.valueOf(value));
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, LEGACY_DATE_TIME_FORMATTER);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(text, DATE_FORMATTER).atStartOfDay();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (Exception ignored) {
        }
        return null;
    }

    private double diffDateTime(Object startValue, Object endValue, double unitMillis) {
        LocalDateTime start = toDateTime(startValue);
        LocalDateTime end = toDateTime(endValue);
        if (start == null || end == null) {
            return 0d;
        }
        return Duration.between(start, end).toMillis() / unitMillis;
    }

    private record EvalResult(Object value, boolean changed, List<FormulaFieldPath> changedFields) {
        static EvalResult of(Object value, boolean changed) {
            return new EvalResult(value, changed, List.of());
        }

        static EvalResult of(Object value, EvalResult left, EvalResult right) {
            List<FormulaFieldPath> changedFields = new ArrayList<>();
            changedFields.addAll(left.changedFields());
            changedFields.addAll(right.changedFields());
            return new EvalResult(value, left.changed() || right.changed(), changedFields);
        }

        static EvalResult of(Object value, List<EvalResult> results) {
            List<FormulaFieldPath> changedFields = new ArrayList<>();
            boolean changed = false;
            for (EvalResult result : results) {
                changed = changed || result.changed();
                changedFields.addAll(result.changedFields());
            }
            return new EvalResult(value, changed, changedFields);
        }

        EvalResult withValue(Object nextValue) {
            return new EvalResult(nextValue, changed, changedFields);
        }

        EvalResult withWriteResult(FormulaFieldWriteResult writeResult) {
            if (!writeResult.changed()) {
                return this;
            }
            List<FormulaFieldPath> nextChangedFields = new ArrayList<>(changedFields);
            nextChangedFields.add(writeResult.fieldPath());
            return new EvalResult(value, true, nextChangedFields);
        }
    }
}
