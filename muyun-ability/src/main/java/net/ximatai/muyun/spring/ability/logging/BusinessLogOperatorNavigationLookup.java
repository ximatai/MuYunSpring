package net.ximatai.muyun.spring.ability.logging;

/** Ability-level display lookup; it cannot add navigation values or grant log visibility. */
public interface BusinessLogOperatorNavigationLookup {
    BusinessLogOperatorNavigationLabels resolve(BusinessLogOperatorNavigation navigation);
}
