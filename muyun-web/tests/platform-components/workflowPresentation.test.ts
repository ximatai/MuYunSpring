import { describe, it, expect } from 'vitest';
import { workflowRouteSelectionTitle, workflowTitle } from '@/platform-components/workflowPresentation';
describe('frozen branch decision labels', () => {
  const route = {
    routeKey: 'r',
    sourceNodeKey: 'branch',
    targetNodeKey: 'approve',
    routeStatus: 'closed',
    routeReason: 'normal_converged',
  };
  const auto = { nodeKey: 'branch', nodeType: 'branch', routeMode: 'auto' };
  it('retains condition/default/unconditional decision meaning after convergence', () => {
    expect(workflowRouteSelectionTitle({ ...route, conditionExpression: '{amount} > 2000' }, auto)).toBe(
      '条件命中',
    );
    expect(workflowRouteSelectionTitle({ ...route, defaultRoute: true }, auto)).toBe('默认出口生效');
    expect(workflowRouteSelectionTitle(route, auto)).toBe('无条件出口生效');
    expect(
      workflowRouteSelectionTitle({ ...route, routeStatus: 'ineffective', defaultRoute: true }, auto),
    ).toBe('默认出口未启用');
  });
  it('does not mistake a dropped selected path for an unselected path or claim it arrived', () => {
    const manual = { ...auto, routeMode: 'manual' };
    expect(workflowRouteSelectionTitle({ ...route, routeStatus: 'dropped' }, manual)).toBe('人工选择');
    expect(workflowRouteSelectionTitle({ ...route, routeStatus: 'ineffective' }, manual)).toBe('人工未选择');
    expect(workflowTitle('normal_converged')).toBe('汇聚已满足');
  });
});
