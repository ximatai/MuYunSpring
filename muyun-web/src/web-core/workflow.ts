import type {
  WorkflowAction,
  WorkflowAddSignExplanation,
  WorkflowBranch,
  WorkflowDesign,
  WorkflowEvent,
  WorkflowHistoryInstance,
  WorkflowRenderBundle,
  WorkflowStatus,
  WorkflowTask,
  WorkflowWorkbenchCard,
  WorkflowWorkbenchFilters,
  WebPageResponse,
} from '@muyun/web-contracts';
import type { HttpClient } from './http';
import { actionResultData } from './actionResult';
import type { ModuleContext } from './module/moduleContext';

/** Workflow mutations change ordinary record rights as well as the record itself. */
export async function refreshWorkflowRecordActions(
  context: Pick<ModuleContext<unknown>, 'invalidateRecordActions' | 'recordActions'>,
  recordId: string,
) {
  context.invalidateRecordActions?.([recordId]);
  await context.recordActions(recordId);
}

/** All operation rights remain server decisions. The client only carries typed intent. */
export function createWorkflowClient(http: HttpClient) {
  const post = async <T>(path: string, body: unknown = {}) =>
    actionResultData<T>(await http.request<T>({ method: 'POST', path, body }));
  const get = <T>(path: string) => http.request<T>({ path });
  const runtime = '/workflow/runtime';
  const recordPath = (moduleAlias: string, recordId: string) =>
    `${runtime}/record/${encodeURIComponent(moduleAlias)}/${encodeURIComponent(recordId)}`;
  const instancePath = (id: string) => `${runtime}/instance/${encodeURIComponent(id)}`;
  return {
    history: async (moduleAlias: string, recordId: string) =>
      (
        await post<{ records: WorkflowHistoryInstance[] }>('/workflow/history/query', {
          moduleAlias,
          recordId,
          page: { pageNum: 1, pageSize: 100 },
        })
      ).records,
    historyBundle: (id: string) =>
      get<WorkflowRenderBundle>(`/workflow/history/${encodeURIComponent(id)}/bundle`),
    historyTasks: async (id: string) =>
      (await get<{ records: WorkflowTask[] }>(`/workflow/history/${encodeURIComponent(id)}/tasks/view`))
        .records,
    historyEvents: async (id: string) =>
      (await get<{ records: WorkflowEvent[] }>(`/workflow/history/${encodeURIComponent(id)}/events/view`))
        .records,
    status: (alias: string, id: string) => post<WorkflowStatus>(`${recordPath(alias, id)}/submit/status`),
    submitBranches: async (alias: string, id: string, payload: unknown = {}) =>
      (await post<{ records: WorkflowBranch[] }>(`${recordPath(alias, id)}/submit/manual-branches`, payload))
        .records,
    executeGuide: (id: string, guideKey: string, payload: unknown) =>
      post<unknown>(
        `${runtime}/task/${encodeURIComponent(id)}/module-task/guides/${encodeURIComponent(guideKey)}/execute`,
        payload,
      ),
    preview: async (alias: string, id: string, payload: unknown = {}) => {
      const result = await post<WorkflowRenderBundle & { tasks: WorkflowTask[]; taskViews?: WorkflowTask[] }>(
        `${recordPath(alias, id)}/submit/preview`,
        payload,
      );
      return {
        ...result,
        semanticJson: result.semanticJson ?? result.instance?.semanticJson,
        layoutJson: result.layoutJson ?? result.instance?.layoutJson,
      };
    },
    submit: (alias: string, id: string, payload: unknown = {}) =>
      post<unknown>(`${recordPath(alias, id)}/actions/submitApproval`, payload),
    bundle: (id: string) => get<WorkflowRenderBundle>(`${instancePath(id)}/bundle`),
    addSignExplanations: async (id: string) =>
      (await get<{ records: WorkflowAddSignExplanation[] }>(`${instancePath(id)}/add-sign-explanations`))
        .records,
    actions: async (id: string) =>
      (await post<{ records: WorkflowAction[] }>(`${instancePath(id)}/actions`)).records,
    tasks: async (id: string) =>
      (await get<{ records: WorkflowTask[] }>(`${instancePath(id)}/tasks`)).records,
    events: async (id: string) =>
      (await get<{ records: WorkflowEvent[] }>(`${instancePath(id)}/events`)).records,
    branches: async (id: string) =>
      (await get<{ records: WorkflowBranch[] }>(`${instancePath(id)}/manual-branches`)).records,
    branchChoices: async (id: string, payload: unknown) =>
      (await post<{ records: WorkflowBranch[] }>(`${instancePath(id)}/manual-branches`, payload)).records,
    taskAction: (id: string, code: string, payload: unknown) =>
      post<unknown>(`${runtime}/task/${encodeURIComponent(id)}/actions/${encodeURIComponent(code)}`, payload),
    instanceAction: (id: string, code: string, payload: unknown) =>
      post<unknown>(`${instancePath(id)}/actions/${encodeURIComponent(code)}`, payload),
    workbench: async (board: string, pageNumber = 0, moduleAlias?: string) =>
      (
        await post<{ records: WorkflowWorkbenchCard[] }>(`${runtime}/workbench/${board}/query`, {
          page: { pageNum: pageNumber + 1, pageSize: 30 },
          moduleAlias,
        })
      ).records,
    workbenchModules: () => get<Record<string, string>>(`${runtime}/workbench/modules`),
    workbenchPage: (
      board: string,
      pageNum = 1,
      filters: WorkflowWorkbenchFilters = {},
      keyword = '',
      pageSize = 30,
    ) =>
      post<WebPageResponse<WorkflowWorkbenchCard> & { navigation?: { modules: Record<string, string> } }>(
        `${runtime}/workbench/${board}/page`,
        {
          query: { ...filters, page: { pageNum, pageSize } },
          keyword,
        },
      ),
    taskViews: async (id: string) =>
      (await get<{ records: WorkflowTask[] }>(`${instancePath(id)}/tasks/view`)).records,
    eventViews: async (id: string) =>
      (await get<{ records: WorkflowEvent[] }>(`${instancePath(id)}/events/view`)).records,
    prepareTask: (id: string) =>
      get<{
        evaluation: {
          passed: boolean;
          failureMessage?: string;
          checkResults: Array<{ checkKey: string; passed: boolean; failureMessage?: string }>;
          guides: Array<{
            guideKey: string;
            title?: string;
            guideKind: string;
            guideConfigText?: string;
            targetModuleAlias?: string;
            targetActionCode?: string;
          }>;
        };
      }>(`${runtime}/task/${encodeURIComponent(id)}/module-task/prepare`),
    design: (base: string, definitionId: string, versionId: string) =>
      get<WorkflowDesign>(
        `${base}/${encodeURIComponent(definitionId)}/versions/${encodeURIComponent(versionId)}/design`,
      ),
  };
}
