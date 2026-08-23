package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChildFormulaReconcilerTest {
    private final ChildFormulaReconciler reconciler = new ChildFormulaReconciler();

    @Test
    void shouldApplyDeclaredSiblingFormulaBeforeAggregateChildPersistence() {
        PositionRow previous = row("previous", true);
        PositionRow selected = row("selected", true);

        reconciler.reconcile("positions", List.of(previous, selected), List.of(row("previous", true)), List.of(formula()));

        assertThat(previous.getPrimaryPosition()).isFalse();
        assertThat(selected.getPrimaryPosition()).isTrue();
    }

    @Test
    void shouldNotResetSiblingsWhenChangedRowIsUnchecked() {
        PositionRow previous = row("previous", true);
        PositionRow selected = row("selected", false);

        reconciler.reconcile("positions", List.of(previous, selected), List.of(row("previous", true)), List.of(formula()));

        assertThat(previous.getPrimaryPosition()).isTrue();
        assertThat(selected.getPrimaryPosition()).isFalse();
    }

    @Test
    void shouldUseWhenConditionToFindTheSourceAmongNewSiblingRows() {
        PositionRow unselected = row(null, false);
        PositionRow selected = row(null, true);

        reconciler.reconcile("positions", List.of(unselected, selected), List.of(), List.of(formula()));

        assertThat(unselected.getPrimaryPosition()).isFalse();
        assertThat(selected.getPrimaryPosition()).isTrue();
    }

    @Test
    void shouldUseDeclaredTriggerRatherThanOnlyTheFormulaTargetField() {
        PositionRow previous = row("previous", true);
        PositionRow selected = row("selected", true);
        selected.setActivatePrimary(true);

        reconciler.reconcile("positions", List.of(previous, selected), List.of(row("previous", true), row("selected", true)),
                List.of(new AggregateChildFormulaDefinition("positions", new FormulaRule("activateExclusivePrimary",
                        "others({positions.primaryPosition}) = false WHEN {positions.activatePrimary}"),
                        List.of("activatePrimary"))));

        assertThat(previous.getPrimaryPosition()).isFalse();
        assertThat(selected.getPrimaryPosition()).isTrue();
    }

    @Test
    void shouldConvertFormulaNumbersToTheDeclaredChildPropertyTypeBeforeWriting() {
        PositionRow previous = row("previous", false);
        previous.setRank(0);
        PositionRow selected = row("selected", false);
        selected.setActivatePrimary(true);
        selected.setRank(0);

        reconciler.reconcile("positions", List.of(previous, selected),
                List.of(row("previous", false), row("selected", false)),
                List.of(new AggregateChildFormulaDefinition("positions", new FormulaRule("setOtherRanks",
                        "others({positions.rank}) = 1 WHEN {positions.activatePrimary}"),
                        List.of("activatePrimary"))));

        assertThat(previous.getRank()).isEqualTo(1).isInstanceOf(Integer.class);
        assertThat(selected.getRank()).isZero();
    }

    private PositionRow row(String id, boolean primaryPosition) {
        PositionRow row = new PositionRow();
        row.setId(id);
        row.setPrimaryPosition(primaryPosition);
        return row;
    }

    private AggregateChildFormulaDefinition formula() {
        return new AggregateChildFormulaDefinition("positions", new FormulaRule("primaryPositionExclusive",
                "others({positions.primaryPosition}) = false WHEN {positions.primaryPosition}"),
                List.of("primaryPosition"));
    }

    private static final class PositionRow extends StandardEntity {
        private Boolean primaryPosition;
        private Boolean activatePrimary;
        private Integer rank;

        public Boolean getPrimaryPosition() {
            return primaryPosition;
        }

        public void setPrimaryPosition(Boolean primaryPosition) {
            this.primaryPosition = primaryPosition;
        }

        public Boolean getActivatePrimary() {
            return activatePrimary;
        }

        public void setActivatePrimary(Boolean activatePrimary) {
            this.activatePrimary = activatePrimary;
        }

        public Integer getRank() {
            return rank;
        }

        public void setRank(Integer rank) {
            this.rank = rank;
        }
    }
}
