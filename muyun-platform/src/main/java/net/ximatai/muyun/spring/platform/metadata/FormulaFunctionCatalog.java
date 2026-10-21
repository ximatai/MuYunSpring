package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Shared authoring descriptions for the platform formula engine. */
public final class FormulaFunctionCatalog {
    private FormulaFunctionCatalog() {}

    public static List<BusinessRuleFunction> functions() {
        return List.of(
                BusinessRuleFunction.declared("platform.formula.function.PRESENT", "PRESENT", "条件判断", "判断字段有值", "判断字段是否已经填写，用于必填校验或决定后续计算是否执行", List.of(
                        new BusinessRuleFunctionParameter("value", "要判断的值")), "BOOLEAN", "PRESENT({supplierId})"),
                BusinessRuleFunction.declared("platform.formula.function.ISNULL", "ISNULL", "条件判断", "判断字段为空", "判断字段是否为空，用于为空时给出提示或补默认处理", List.of(
                        new BusinessRuleFunctionParameter("value", "要判断的值")), "BOOLEAN", "ISNULL({supplierId})"),
                BusinessRuleFunction.declared("platform.formula.function.IN", "IN", "条件判断", "匹配候选项", "判断字段是否匹配给定候选项，用于状态、类型等有限枚举的判断", List.of(
                        new BusinessRuleFunctionParameter("value", "要判断的值"),
                        new BusinessRuleFunctionParameter("candidates", "一个或多个候选值")), "BOOLEAN", "IN({status}, 'draft', 'active')"),
                BusinessRuleFunction.declared("platform.formula.function.TODAY", "TODAY", "日期时间", "获取今天", "取得当前日期，适合计算当天生效、截止日期等规则", List.of(),
                        "DATE", "TODAY()"),
                BusinessRuleFunction.declared("platform.formula.function.NOW", "NOW", "日期时间", "获取当前时间", "取得当前 UTC 时间，适合记录当前时刻或比较时效", List.of(),
                        "TIMESTAMP", "NOW()"),
                BusinessRuleFunction.declared("platform.formula.function.YEAR", "YEAR", "日期时间", "提取年份", "从日期或时间中提取年份，适合按年度计算或校验", List.of(
                        new BusinessRuleFunctionParameter("value", "日期或时间字段")), "INTEGER", "YEAR({signedAt})"),
                BusinessRuleFunction.declared("platform.formula.function.MONTH", "MONTH", "日期时间", "提取月份", "从日期或时间中提取月份，适合按月判断或分组计算", List.of(
                        new BusinessRuleFunctionParameter("value", "日期或时间字段")), "INTEGER", "MONTH({signedAt})"),
                BusinessRuleFunction.declared("platform.formula.function.DAY", "DAY", "日期时间", "提取日期", "从日期或时间中提取日，适合按具体日期触发规则", List.of(
                        new BusinessRuleFunctionParameter("value", "日期或时间字段")), "INTEGER", "DAY({signedAt})"),
                BusinessRuleFunction.declared("platform.formula.function.DATE_ADD", "DATE_ADD", "日期时间", "增加日期", "在日期基础上增加天数，适合推算到期日、提醒日", List.of(
                        new BusinessRuleFunctionParameter("date", "yyyy-MM-dd 日期"),
                        new BusinessRuleFunctionParameter("days", "整数天数")), "DATE", "DATE_ADD({signedDate}, 7)"),
                BusinessRuleFunction.declared("platform.formula.function.DATE_SUB", "DATE_SUB", "日期时间", "减少日期", "在日期基础上减少天数，适合推算提前提醒日、最早日期", List.of(
                        new BusinessRuleFunctionParameter("date", "yyyy-MM-dd 日期"),
                        new BusinessRuleFunctionParameter("days", "整数天数")), "DATE", "DATE_SUB({signedDate}, 7)"),
                BusinessRuleFunction.declared("platform.formula.function.DATETIME_ADD", "DATETIME_ADD", "日期时间", "增加时间", "在时间基础上增加指定间隔，适合计算精确的截止时间", List.of(
                        new BusinessRuleFunctionParameter("dateTime", "UTC 时间"),
                        new BusinessRuleFunctionParameter("amount", "整数间隔"),
                        new BusinessRuleFunctionParameter("unit", "DAY/HOUR/MINUTE/SECOND")), "TIMESTAMP",
                        "DATETIME_ADD({signedAt}, 2, 'HOUR')"),
                BusinessRuleFunction.declared("platform.formula.function.DATETIME_SUB", "DATETIME_SUB", "日期时间", "减少时间", "在时间基础上减少指定间隔，适合计算提前触发时间", List.of(
                        new BusinessRuleFunctionParameter("dateTime", "UTC 时间"),
                        new BusinessRuleFunctionParameter("amount", "整数间隔"),
                        new BusinessRuleFunctionParameter("unit", "DAY/HOUR/MINUTE/SECOND")), "TIMESTAMP",
                        "DATETIME_SUB({signedAt}, 2, 'HOUR')"),
                BusinessRuleFunction.declared("platform.formula.function.DATE_DIFF_DAYS", "DATE_DIFF_DAYS", "日期时间", "计算相差天数", "计算两个日期或时间相差的天数，适合期限与逾期判断", List.of(
                        new BusinessRuleFunctionParameter("start", "开始日期或时间"),
                        new BusinessRuleFunctionParameter("end", "结束日期或时间")), "DECIMAL",
                        "DATE_DIFF_DAYS({startDate}, {endDate})"),
                BusinessRuleFunction.declared("platform.formula.function.DATE_DIFF_HOURS", "DATE_DIFF_HOURS", "日期时间", "计算相差小时", "计算两个时间相差的小时数，适合时效与工时判断", List.of(
                        new BusinessRuleFunctionParameter("start", "开始时间"),
                        new BusinessRuleFunctionParameter("end", "结束时间")), "DECIMAL",
                        "DATE_DIFF_HOURS({startAt}, {endAt})"),
                BusinessRuleFunction.declared("platform.formula.function.COUNT", "COUNT", "子表汇总", "统计非空值", "统计子表字段中已填写的记录数，适合检查明细是否达到最低数量", List.of(
                        new BusinessRuleFunctionParameter("field", "子表字段，例如 {lines.amount}")), "LONG",
                        "COUNT({lines.amount})"),
                BusinessRuleFunction.declared("platform.formula.function.SUM", "SUM", "子表汇总", "汇总求和", "汇总子表中的数值，适合计算明细总金额、总数量", List.of(
                        new BusinessRuleFunctionParameter("field", "子表数值字段，例如 {lines.amount}")), "DECIMAL",
                        "SUM({lines.amount})"),
                BusinessRuleFunction.declared("platform.formula.function.AVG", "AVG", "子表汇总", "计算平均值", "计算子表数值的平均值，适合得到平均单价、平均分等指标", List.of(
                        new BusinessRuleFunctionParameter("field", "子表数值字段，例如 {lines.amount}")), "DECIMAL",
                        "AVG({lines.amount})"),
                BusinessRuleFunction.declared("platform.formula.function.MAX", "MAX", "子表汇总", "取得最大值", "取得子表数值中的最大值，适合识别最高金额、最大数量", List.of(
                        new BusinessRuleFunctionParameter("field", "子表数值字段，例如 {lines.amount}")), "DECIMAL",
                        "MAX({lines.amount})"),
                BusinessRuleFunction.declared("platform.formula.function.MIN", "MIN", "子表汇总", "取得最小值", "取得子表数值中的最小值，适合识别最低金额、最小数量", List.of(
                        new BusinessRuleFunctionParameter("field", "子表数值字段，例如 {lines.amount}")), "DECIMAL",
                        "MIN({lines.amount})"),
                BusinessRuleFunction.declared("platform.formula.function.ROUND", "ROUND", "数值处理", "数值四舍五入", "对数值四舍五入，适合把计算结果收敛到指定的小数位", List.of(
                        new BusinessRuleFunctionParameter("value", "数值或表达式"),
                        new BusinessRuleFunctionParameter("scale", "保留 0–12 位小数")), "DECIMAL",
                        "ROUND({amount}, 2)"),
                BusinessRuleFunction.declared("platform.formula.function.FORMAT_DECIMAL", "FORMAT_DECIMAL", "数值处理", "格式化小数位", "把数值格式化为固定小数位文本，适合展示或拼接时保留末尾零", List.of(
                        new BusinessRuleFunctionParameter("value", "数值或表达式"),
                        new BusinessRuleFunctionParameter("scale", "保留 0–12 位小数")), "STRING",
                        "FORMAT_DECIMAL({amount}, 2)")
        );
    }
}
