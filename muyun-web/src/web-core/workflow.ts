import type {
  WorkflowDefinition,
  WorkflowVersion,
  WorkflowTaskPreparation,
  WorkflowConfigurationCatalog,
  WorkflowDefinitionSelection,
  WorkflowDefinitionCreate,
  WorkflowAdminInstance,
  WorkflowAdminTask,
  WorkflowAdminInstanceQuery,
  WorkflowAdminHistoryQuery,
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

function workflowRequests(http: HttpClient) {
  return {
    post: async <T>(path: string, body: unknown = {}) =>
      actionResultData<T>(await http.request<T>({ method: 'POST', path, body })),
    get: <T>(path: string) => http.request<T>({ path }),
  };
}

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
  const { post, get } = workflowRequests(http);
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
      get<WorkflowTaskPreparation>(`${runtime}/task/${encodeURIComponent(id)}/module-task/prepare`),
  };
}

/** Definition commands are scoped to the owning business module. */
export function createWorkflowDefinitionClient(http: HttpClient, moduleAlias: string) {
  const base = `/platform.module/${encodeURIComponent(moduleAlias)}/workflow-definitions`;
  const { post, get } = workflowRequests(http);
  const definition = (id: string) => `${base}/${encodeURIComponent(id)}`;
  const version = (id: string, versionId: string) =>
    `${definition(id)}/versions/${encodeURIComponent(versionId)}`;
  return {
    catalog: (id: string) =>
      http.request<WorkflowConfigurationCatalog>({ path: `${definition(id)}/configuration-catalog` }),
    query: async () =>
      (
        await post<{ records: WorkflowDefinition[] }>(`${base}/query`, {
          page: { pageNum: 1, pageSize: 200 },
        })
      ).records,
    versions: async (id: string) =>
      (
        await post<{ records: WorkflowVersion[] }>(`${definition(id)}/versions/query`, {
          page: { pageNum: 1, pageSize: 200 },
        })
      ).records,
    create: (payload: WorkflowDefinitionCreate) => post<WorkflowDefinition>(`${base}/insert`, payload),
    view: (id: string) => get<WorkflowDefinition>(`${base}/view/${encodeURIComponent(id)}`),
    upgrade: (id: string) => post<WorkflowVersion>(`${definition(id)}/upgrade`),
    saveSelection: (id: string, payload: WorkflowDefinitionSelection) =>
      post<WorkflowDefinition>(`${definition(id)}/selection`, payload),
    design: (id: string, versionId: string) => get<WorkflowDesign>(`${version(id, versionId)}/design`),
    saveDesign: (id: string, versionId: string, expectedVersion: number, design: WorkflowDesign) =>
      post<WorkflowVersion>(`${version(id, versionId)}/design`, { version: expectedVersion, design }),
    validate: (id: string, versionId: string) => post<unknown>(`${version(id, versionId)}/validate`),
    publish: (id: string, versionId: string, definitionVersion: number, expectedVersion: number) =>
      post<WorkflowVersion>(`${version(id, versionId)}/publish`, {
        definitionVersion,
        version: expectedVersion,
      }),
    changeStatus: (id: string, action: 'disable' | 'archive', expectedVersion: number) =>
      post<WorkflowDefinition>(`${definition(id)}/${action}`, { version: expectedVersion }),
  };
}

/** Management projections and commands keep their separate authorization boundary. */
export function createWorkflowAdminClient(http: HttpClient) {
  const base = '/workflow/runtime/admin';
  const requests = workflowRequests(http);
  const post = <T>(path: string, body: unknown = {}) => requests.post<T>(`${base}${path}`, body);
  const item = (id: string, history: boolean) =>
    `/${history ? 'history' : 'instance'}/${encodeURIComponent(id)}`;
  return {
    instances: async (query: WorkflowAdminInstanceQuery) =>
      (await post<{ records: WorkflowAdminInstance[] }>('/instance/query', query)).records,
    history: async (query: WorkflowAdminHistoryQuery) =>
      (await post<{ records: WorkflowHistoryInstance[] }>('/history/query', query)).records,
    bundle: (id: string, history: boolean) => post<WorkflowRenderBundle>(`${item(id, history)}/bundle`),
    events: async (id: string, history: boolean) =>
      (await post<{ records: WorkflowEvent[] }>(`${item(id, history)}/events/view`)).records,
    activeTasks: async (id: string) =>
      (
        await http.request<{ records: WorkflowAdminTask[] }>({
          path: `${base}${item(id, false)}/active-tasks`,
        })
      ).records,
    execute: (instanceId: string, code: string, reason: string, taskId?: string) =>
      post<unknown>(
        `/${taskId ? 'task' : 'instance'}/${encodeURIComponent(taskId ?? instanceId)}/actions/${encodeURIComponent(code)}`,
        { reason },
      ),
  };
}
