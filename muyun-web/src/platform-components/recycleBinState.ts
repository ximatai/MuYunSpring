import { computed, ref, shallowRef, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue';
import type {
  PurgeReport,
  RecycleBinItem,
  RestoreReport,
  WebActionResultEnvelope,
  WebPageResponse,
  WebQueryRequest,
} from '@muyun/web-contracts';
import { actionResultData, type ModuleContext } from '@muyun/web-core';
import { presentPlatformError } from './platformErrorFeedback';
import { recordLabelOf } from './actionConfirmation';
import { handlePlatformActionSuccess } from './platformActionResultFeedback';

export interface RecycleBinStateOptions<TRecord> {
  context: MaybeRefOrGetter<ModuleContext<TRecord>>;
  recordTitle?: (record: TRecord) => string;
  /** 外部刷新信号，变化时重新加载列表 */
  reloadKey?: Ref<number>;
}

/** Turns lifecycle facts from the platform into concise operator-facing guidance. */
export function recycleBinRestoreUnavailableReason(item: RecycleBinItem<unknown>): string | undefined {
  if (item.restorable) return undefined;
  switch (item.unavailableReason) {
    case 'deletion history is unavailable':
      return '无法恢复：删除历史不可用';
    case 'resource lifecycle changed after deletion':
      return '无法恢复：删除后资源生命周期已变化';
    default:
      return item.unavailableReason
        ? `无法恢复：${item.unavailableReason}`
        : '无法恢复：当前记录不满足恢复条件';
  }
}

type RecycleBinReports = { restore: RestoreReport; purge: PurgeReport };
type RecycleBinAction = keyof RecycleBinReports;

export function useRecycleBinState<TRecord>(options: RecycleBinStateOptions<TRecord>) {
  const items = ref<RecycleBinItem<TRecord>[]>([]);
  const pendingActions = shallowRef<
    Array<{
      action: RecycleBinAction;
      item: RecycleBinItem<TRecord>;
      report: RestoreReport | PurgeReport;
    }>
  >([]);

  function rememberOutcome(
    action: RecycleBinAction,
    item: RecycleBinItem<TRecord>,
    report: RestoreReport | PurgeReport,
  ) {
    const remaining = pendingActions.value.filter(
      (entry) =>
        entry.item.sourceDeleteOperationId !== item.sourceDeleteOperationId || entry.action !== action,
    );
    if (report.entries.some((entry) => entry.status !== (action === 'restore' ? 'RESTORED' : 'PURGED'))) {
      remaining.push({ action, item, report });
    }
    pendingActions.value = remaining;
  }

  const loading = ref(false);
  const acting = ref(false);
  const actingOperationId = ref<string>();
  const total = ref(0);
  const summaryTotal = ref<number>();
  const pageNum = ref(1);
  const pageSize = ref(20);
  let lastRequest: WebQueryRequest = defaultQueryRequest();
  let loadRequestSeq = 0;
  let summaryRequestSeq = 0;
  let contextGeneration = 0;
  watch(
    () => toValue(options.context),
    () => {
      contextGeneration++;
      acting.value = false;
      actingOperationId.value = undefined;
      lastRequest = defaultQueryRequest();
      pageNum.value = 1;
      pageSize.value = 200;
      pendingActions.value = [];
      items.value = [];
      total.value = 0;
      summaryTotal.value = undefined;
      loadRequestSeq++;
      summaryRequestSeq++;
      loading.value = false;
    },
    { flush: 'sync' },
  );

  if (options.reloadKey) {
    watch(options.reloadKey, () => void load());
  }

  const isEmpty = computed(() => !loading.value && items.value.length === 0);

  function recordTitleOf(item: RecycleBinItem<TRecord>): string {
    if (options.recordTitle) {
      return options.recordTitle(item.record);
    }
    const record = item.record as Record<string, unknown>;
    const label = recordLabelOf(toValue(options.context).runtime.snapshot()?.uiDescriptor, record);
    if (label) return label;
    return String(record.title ?? record.alias ?? record.id ?? '未命名记录');
  }

  async function load(request: WebQueryRequest = lastRequest): Promise<boolean> {
    const requestSeq = ++loadRequestSeq;
    const context = toValue(options.context);
    loading.value = true;
    lastRequest = request;
    try {
      const response = await context.http.request<WebPageResponse<RecycleBinItem<TRecord>>>({
        method: 'POST',
        path: `/${context.moduleAlias}/recycle-bin/query`,
        body: request,
      });
      if (requestSeq !== loadRequestSeq || context !== toValue(options.context)) return false;
      items.value = response.records;
      total.value = response.total;
      summaryTotal.value = response.total;
      pageNum.value = response.pageNum;
      pageSize.value = response.pageSize;
      return true;
    } catch (cause) {
      if (requestSeq !== loadRequestSeq || context !== toValue(options.context)) return false;
      items.value = [];
      total.value = 0;
      presentPlatformError(cause, { source: 'recycle-bin', phase: 'load' });
      return false;
    } finally {
      if (requestSeq === loadRequestSeq) loading.value = false;
    }
  }

  async function refreshSummary(): Promise<number | undefined> {
    const requestSeq = ++summaryRequestSeq;
    const context = toValue(options.context);
    try {
      const response = await context.http.request<WebPageResponse<RecycleBinItem<TRecord>>>({
        method: 'POST',
        path: `/${context.moduleAlias}/recycle-bin/query`,
        body: { page: { pageNum: 1, pageSize: 1 }, conditions: [], sorts: [] },
      });
      if (requestSeq !== summaryRequestSeq || context !== toValue(options.context)) return undefined;
      summaryTotal.value = response.total;
      return response.total;
    } catch {
      if (requestSeq === summaryRequestSeq) summaryTotal.value = undefined;
      return undefined;
    }
  }

  function restore(item: RecycleBinItem<TRecord>, reload = true) {
    return executeAction('restore', item, reload);
  }

  function purge(item: RecycleBinItem<TRecord>, reload = true) {
    return executeAction('purge', item, reload);
  }

  async function executeAction<A extends RecycleBinAction>(
    action: A,
    item: RecycleBinItem<TRecord>,
    reload: boolean,
  ): Promise<RecycleBinReports[A] | undefined> {
    const available = action === 'restore' ? item.restorable : item.purgeable;
    if (acting.value || !available || !item.sourceDeleteOperationId) return undefined;
    const generation = contextGeneration;
    const context = toValue(options.context);
    const isCurrent = () => generation === contextGeneration && context === toValue(options.context);
    acting.value = true;
    actingOperationId.value = item.sourceDeleteOperationId;
    try {
      const result = await context.http.request<
        RecycleBinReports[A] | WebActionResultEnvelope<RecycleBinReports[A]>
      >({
        method: 'POST',
        path: `/${context.moduleAlias}/recycle-bin/${encodeURIComponent(item.sourceDeleteOperationId)}/${action}`,
      });
      if (!isCurrent()) return undefined;
      const report = actionResultData(result);
      rememberOutcome(action, item, report);
      await handlePlatformActionSuccess(result, {
        fallbackMessage: actionFallbackMessage(action, report, recordTitleOf(item)),
        source: 'recycle-bin',
        phase: 'action',
      });
      if (!isCurrent()) return undefined;
      if (reload) await load();
      return isCurrent() ? report : undefined;
    } catch (cause) {
      if (!isCurrent()) return undefined;
      presentPlatformError(cause, { source: 'recycle-bin', phase: 'action' });
      return undefined;
    } finally {
      if (generation === contextGeneration) {
        acting.value = false;
        actingOperationId.value = undefined;
      }
    }
  }

  return {
    pendingActions,
    items,
    loading,
    acting,
    actingOperationId,
    total,
    summaryTotal,
    pageNum,
    pageSize,
    isEmpty,
    recordTitleOf,
    load,
    refreshSummary,
    restore,
    purge,
  };
}

function defaultQueryRequest(): WebQueryRequest {
  return {
    page: { pageNum: 1, pageSize: 200 },
    conditions: [],
    sorts: [],
  };
}

function actionFallbackMessage(action: RecycleBinAction, report: RestoreReport | PurgeReport, title: string) {
  const verb = action === 'restore' ? '恢复' : '彻底删除';
  const successStatus = action === 'restore' ? 'RESTORED' : 'PURGED';
  const completed = report.entries.filter((entry) => entry.status === successStatus).length;
  const skipped = report.entries.filter((entry) => entry.status === 'SKIPPED').length;
  const failed = report.entries.filter((entry) => entry.status === 'FAILED').length;
  if (completed === 0) {
    return `「${title}」${verb}未完成：成功 0，跳过 ${skipped}，失败 ${failed}`;
  }
  if (failed > 0 || skipped > 0) {
    return `「${title}」${verb}完成：成功 ${completed}，跳过 ${skipped}，失败 ${failed}`;
  }
  return `「${title}」${verb}成功`;
}
