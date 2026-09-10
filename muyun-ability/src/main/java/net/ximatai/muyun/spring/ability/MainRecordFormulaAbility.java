package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.List;

/**
 * Opts a static service into server-authoritative main-record formulas during save.
 *
 * <p>Only {@code BEFORE_SAVE} main-record calculations and validations are supported here.
 * Readable JavaBean business properties form the formula field boundary; a calculation target
 * must also have a JavaBean setter. Platform standard fields and read-only title companions are
 * not formula fields and cannot be written by formulas. Formula definitions stay with the static
 * service so they can also be projected by a delivery adapter without creating another Java-side
 * formula model.</p>
 */
public interface MainRecordFormulaAbility<T extends EntityContract> extends CrudAbility<T> {
    default List<FormulaRule> mainRecordFormulaRules() {
        return List.of();
    }
}
