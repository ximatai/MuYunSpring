import { describe, expect, it, vi } from 'vitest';
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
