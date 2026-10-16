import { describe, expect, it } from 'vitest';
import { createLanguageContext } from '@muyun/web-core';
import type { TextPresentation, WorkflowRenderBundle } from '@muyun/web-contracts';
import {
  refreshWorkflowTextResources,
  workflowNodePresentation,
  workflowTitle,
  workflowRouteSelectionTitle,
} from '@/platform-components/workflowPresentation';

function text(version: string, english: string): TextPresentation {
  return {
    source: {
      sourceKind: 'CONFIG',
      sourceId: 'workflow/version-1/node',
      sourceVersion: version,
      originalText: '原审批节点',
    },
    languages: { en: { text: english, requestedTag: 'en', missing: false, fallback: false } },
  };
}
it('refreshes only matching frozen workflow resources and preserves instance, route and original node facts', () => {
  const original: WorkflowRenderBundle = {
    instance: { id: 'instance', moduleAlias: 'demo.order', recordId: 'record', instanceStatus: 'running' },
    nodes: [
      {
        id: 'node',
        nodeKey: 'approve',
        nodeType: 'approval',
        nodeStatus: 'active',
        nodeTitle: '原审批节点',
        nodeTitleText: text('v1', 'Previous approval'),
      },
    ],
    routes: [],
    semanticJson: 'frozen semantic facts',
    layoutJson: 'frozen layout',
  };
  const response = {
    ...original,
    instance: { ...original.instance, instanceStatus: 'terminated' },
    nodes: [
      {
        ...original.nodes[0]!,
        nodeStatus: 'completed',
        nodeTitle: '服务端展示副本',
        nodeTitleText: text('v1', 'Corrected approval'),
      },
    ],
  };
  const refreshed = refreshWorkflowTextResources(original, response);
  expect(refreshed.instance).toBe(original.instance);
  expect(refreshed.routes).toBe(original.routes);
  expect(refreshed.nodes[0]?.nodeStatus).toBe('active');
  expect(refreshed.nodes[0]?.nodeTitle).toBe('原审批节点');
  const language = createLanguageContext({ languageTag: 'en' });
  expect(workflowNodePresentation(refreshed.nodes[0]!, language).nodeTitle).toBe('Corrected approval');
  expect(workflowTitle('approve', language)).toBe('Approve');
  const newerOriginal = {
    ...response,
    nodes: [{ ...response.nodes[0]!, nodeTitleText: text('v2', 'New version approval') }],
  };
  expect(refreshWorkflowTextResources(original, newerOriginal).nodes[0]).toBe(original.nodes[0]);
  expect(original.nodes[0]?.nodeTitleText?.languages.en?.text).toBe('Previous approval');
});

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
    expect(workflowTitle('forceTerminate')).toBe('强制终止');
    expect(workflowTitle('reset')).toBe('重置审批');
  });
});
