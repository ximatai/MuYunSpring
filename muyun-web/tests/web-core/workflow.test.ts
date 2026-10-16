import { describe, expect, expectTypeOf, it, vi } from 'vitest';
import { createWorkflowClient, refreshWorkflowRecordActions } from '@/web-core/workflow';
import type { HttpClient } from '@/web-core/http';

describe('workflow client public contracts', () => {
  it('unwraps standard mutation receipts while retaining plain read results', async () => {
    const data = { instance: { id: 'instance' }, continued: true };
    const request = vi.fn(async () => ({ data, changeSetId: 'committed', changes: [] }));
    const client = createWorkflowClient({ request } as HttpClient);
    expect(await client.submit('demo.purchase', 'r')).toEqual(data);
    expect(await client.taskAction('task', 'approve', {})).toEqual(data);
    request.mockImplementation(async () => data as never);
    expect(await client.submit('demo.purchase', 'r')).toEqual(data);
  });
  it('projects submit preview frozen semantic and layout snapshots from the instance', async () => {
    const semanticJson = '{"nodes":[],"links":[]}',
      layoutJson = '{"version":1,"nodes":{}}';
    const response = {
      instance: { id: 'preview', semanticJson, layoutJson },
      nodes: [],
      routes: [],
      tasks: [],
      taskViews: [],
    };
    const request = vi.fn(async () => response);
    const client = createWorkflowClient({ request } as HttpClient);
    expect(await client.preview('demo.purchase', 'record')).toMatchObject({
      semanticJson,
      layoutJson,
      tasks: [],
      taskViews: [],
    });
    expect(response).not.toHaveProperty('layoutJson');
  });
  it('carries exact paging and title filters together and resolves user-facing projections', async () => {
    const request = vi.fn(async () => ({
      records: [],
      total: 42,
      pages: 3,
      navigation: { modules: { 'demo.purchase': '采购' } },
    }));
    const client = createWorkflowClient({ request } as HttpClient);
    const result = await client.workbenchPage(
      'todo',
      2,
      { overtimeStatus: 'warned', receivedTo: '2026-10-09T00:00:00Z' },
      '采购',
      20,
    );
    expect(result.total).toBe(42);
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/workflow/runtime/workbench/todo/page',
      body: {
        query: {
          overtimeStatus: 'warned',
          receivedTo: '2026-10-09T00:00:00Z',
          page: { pageNum: 2, pageSize: 20 },
        },
        keyword: '采购',
      },
    });
    await client.taskViews('i/1');
    expect(request).toHaveBeenLastCalledWith({ path: '/workflow/runtime/instance/i%2F1/tasks/view' });
    await client.eventViews('i/1');
    expect(request).toHaveBeenLastCalledWith({ path: '/workflow/runtime/instance/i%2F1/events/view' });
    await client.workbenchModules();
    expect(request).toHaveBeenLastCalledWith({ path: '/workflow/runtime/workbench/modules' });
  });
  it('unwraps server records for tasks, actions, manual routes, workbench and archived instances', async () => {
    const records = [{ id: 'one' }];
    const request = vi.fn(async () => ({ records }));
    const client = createWorkflowClient({ request } as HttpClient);
    for (const result of await Promise.all([
      client.tasks('instance'),
      client.addSignExplanations('instance'),
      client.events('instance'),
      client.actions('instance'),
      client.branches('instance'),
      client.submitBranches('demo.purchase', 'record'),
      client.workbench('todo', 1, 'demo.purchase'),
      client.history('demo.purchase', 'record'),
      client.historyTasks('archive'),
      client.historyEvents('archive'),
    ]))
      expect(result).toBe(records);
    expect(request).toHaveBeenCalledWith({
      method: 'POST',
      path: '/workflow/runtime/workbench/todo/query',
      body: { page: { pageNum: 2, pageSize: 30 }, moduleAlias: 'demo.purchase' },
    });
  });

  it('carries selected paths when requesting the next manual branch frontier', async () => {
    const request = vi.fn(async () => ({ records: [] }));
    const client = createWorkflowClient({ request } as HttpClient);
    const manualRouteSelections = [{ branchNodeKey: 'outer', routeKey: 'nested' }];
    await client.submitBranches('demo.purchase', 'r', { manualRouteSelections });
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/workflow/runtime/record/demo.purchase/r/submit/manual-branches',
      body: { manualRouteSelections },
    });
    await client.branchChoices('i/1', { taskId: 't', manualRouteSelections });
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/workflow/runtime/instance/i%2F1/manual-branches',
      body: { taskId: 't', manualRouteSelections },
    });
  });

  it('saves business values through the task guide transaction with optimistic version', async () => {
    const request = vi.fn(async () => ({ actionResult: { task: { taskStatus: 'done' } } }));
    const client = createWorkflowClient({ request } as HttpClient);
    await client.executeGuide('task/id', 'receive', {
      version: 3,
      values: { delivered: true },
      reason: 'arrived',
    });
    expect(request).toHaveBeenCalledExactlyOnceWith({
      method: 'POST',
      path: '/workflow/runtime/task/task%2Fid/module-task/guides/receive/execute',
      body: { version: 3, values: { delivered: true }, reason: 'arrived' },
    });
  });
  it('invalidates the same-record rights snapshot before awaiting refreshed workflow permissions', async () => {
    const calls: string[] = [];
    let complete!: () => void;
    const request = new Promise<void>((resolve) => (complete = resolve));
    const context = {
      invalidateRecordActions: (ids?: string[]) => calls.push(`invalidate:${ids?.join(',')}`),
      recordActions: async (id: string) => {
        calls.push(`reload:${id}`);
        await request;
        return { recordId: id, actions: [] };
      },
    };
    const refreshed = refreshWorkflowRecordActions(context, 'record');
    expect(calls).toEqual(['invalidate:record', 'reload:record']);
    complete();
    await refreshed;
  });
});

describe('workflow definition and management clients', () => {
  it('scopes definition commands and carries both optimistic versions through standard receipts', async () => {
    const { createWorkflowDefinitionClient } = await import('@/web-core/workflow');
    const data = { id: 'version', version: 4, versionNo: 2, publishStatus: 'published' };
    const request = vi.fn(async () => ({ data, changeSetId: 'committed', changes: [] }));
    const client = createWorkflowDefinitionClient({ request } as HttpClient, 'demo/purchase');
    expect(await client.publish('definition/1', 'version/2', 7, 3)).toEqual(data);
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/platform.module/demo%2Fpurchase/workflow-definitions/definition%2F1/versions/version%2F2/publish',
      body: { definitionVersion: 7, version: 3 },
    });
    const design = { nodes: [], links: [] };
    await client.saveDesign('definition/1', 'version/2', 3, design);
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ body: { version: 3, design } }));
  });

  it('unwraps management records and preserves the separate history and task command paths', async () => {
    const { createWorkflowAdminClient } = await import('@/web-core/workflow');
    const records = [{ instanceId: 'one' }];
    const request = vi.fn(async () => ({ records }));
    const client = createWorkflowAdminClient({ request } as HttpClient);
    const query = { instanceStatus: 'terminated', page: { pageNum: 2, pageSize: 30 } };
    expect(await client.instances(query)).toBe(records);
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/workflow/runtime/admin/instance/query',
      body: query,
    });
    await client.events('archive/1', true);
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/workflow/runtime/admin/history/archive%2F1/events/view',
      body: {},
    });
    await client.execute('instance/1', 'forceApprove', '恢复办理', 'task/2');
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/workflow/runtime/admin/task/task%2F2/actions/forceApprove',
      body: { reason: '恢复办理' },
    });
  });

  it('exposes history starter filters separately from current instance filters', async () => {
    const { createWorkflowAdminClient } = await import('@/web-core/workflow');
    const records = [{ instanceId: 'archived' }];
    const request = vi.fn(async () => ({ records }));
    const client = createWorkflowAdminClient({ request } as HttpClient);
    expectTypeOf<Parameters<typeof client.history>[0]>().not.toHaveProperty('instanceStatus');
    const historyQuery = {
      moduleAlias: 'demo.purchase',
      recordId: 'record',
      startedBy: 'starter',
      page: { pageNum: 1, pageSize: 30 },
    };
    expect(await client.history(historyQuery)).toBe(records);
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/workflow/runtime/admin/history/query',
      body: historyQuery,
    });
    const instanceQuery = {
      moduleAlias: 'demo.purchase',
      starterId: 'starter',
      instanceStatus: 'running',
      approvalStatus: 'approved',
      currentAssigneeId: 'assignee',
      overtimeStatus: 'overdue',
      page: { pageNum: 2, pageSize: 30 },
    };
    await client.instances(instanceQuery);
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/workflow/runtime/admin/instance/query',
      body: instanceQuery,
    });
  });
});
