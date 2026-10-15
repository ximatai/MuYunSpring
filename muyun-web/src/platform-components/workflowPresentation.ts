import type {
  WorkflowNode,
  WorkflowRoute,
  WorkflowRenderBundle,
  WorkflowTaskPreparation,
  TextPresentation,
} from '@muyun/web-contracts';
import { presentationLabel, platformLanguageContext, type LanguageContext } from '@muyun/web-core';
const titles: Record<string, string> = {
  normal: '正常',
  route_dropped: '分支待办取消',
  warned: '预警',
  overdue: '超期',
  instance_started: '已提交',
  instance_completed: '流程完成',
  task_rejected: '驳回',
  task_resubmitted: '重新提交',
  task_transferred: '转办',
  node_rolled_back: '回退',
  instance_revoked: '撤回',
  instance_reset: '重置审批',
  instance_terminated: '终止流程',
  instance_force_terminated: '强制终止',
  forceTerminate: '强制终止',
  reset: '重置审批',
  add_sign: '加签',
  start: '开始',
  end: '结束',
  approval: '审批',
  task: '业务任务',
  business: '业务任务',
  route: '路径推进',
  approval_milestone: '审批完成',
  resubmit_required: '等待重新提交',
  branch: '分支',
  converge: '汇聚',
  milestone: '里程碑',
  any: '任一通过',
  all: '全部通过',
  ratio: '比例通过',
  notice: '通知',
  manual: '手动选择',
  auto: '条件选择',
  approval_completed: '审批完成',
  draft: '草稿',
  published: '已发布',
  disabled: '已停用',
  archived: '已归档',
  unsubmitted: '未提交',
  no_workflow: '未配置可用流程',
  match_error: '流程匹配冲突',
  running: '进行中',
  processing: '审批中',
  approved: '已批准',
  rejected: '已驳回',
  revoked: '已撤回',
  completed: '已完成',
  terminated: '已终止',
  todo: '待办理',
  done: '已办理',
  transferred: '已转办',
  canceled: '已取消',
  noticed: '已阅',
  rolled_back: '已回退',
  active: '当前节点',
  inactive: '未到达',
  pending: '未到达',
  waiting: '等待汇聚',
  skipped: '已跳过',
  effective: '有效路径',
  ineffective: '未选路径',
  dropped: '已放弃',
  closed: '已关闭',
  candidate: '待选择',
  condition_matched: '条件命中',
  condition_unmatched: '条件未命中',
  default_selected: '默认出口生效',
  manual_selected: '人工选择',
  manual_unselected: '人工未选择',
  converge_reached: '已到达汇聚',
  normal_converged: '汇聚已满足',
  submit: '提交',
  submitApproval: '提交审批',
  approve: '通过',
  reject: '驳回',
  rollback: '回退',
  resubmit: '重新提交',
  complete: '完成任务',
  revoke: '撤回',
  revokeApprove: '撤销通过',
  transfer: '转办',
  addSign: '加签',
  read: '已阅',
  submitted: '已提交',
  node_activated: '节点激活',
  task_created: '任务创建',
  task_completed: '任务完成',
};
export function workflowTitle(value?: string, language: LanguageContext = platformLanguageContext): string {
  if (!value) return '—';
  const key = Object.hasOwn(titles, value) ? value : value.toLowerCase();
  return Object.hasOwn(titles, key) ? language.t(`platform.workflow.${key}`, titles[key]) : value;
}

export function workflowNodePresentation<T extends WorkflowNode>(node: T, language: LanguageContext): T {
  return {
    ...node,
    nodeTitle: presentationLabel(node.nodeTitle ?? node.title ?? node.nodeKey, node.nodeTitleText, language),
  };
}

export function sameWorkflowTextSource(
  current: TextPresentation | undefined,
  next: TextPresentation | undefined,
) {
  return Boolean(
    current &&
    next &&
    current.source.sourceKind === next.source.sourceKind &&
    current.source.sourceId === next.source.sourceId &&
    current.source.sourceVersion === next.source.sourceVersion &&
    current.source.originalText === next.source.originalText,
  );
}

/** Frozen declarations refresh text independently of the latest check verdict; captured evidence stays intact. */
export function refreshWorkflowTaskTextResources(
  current: WorkflowTaskPreparation,
  next: WorkflowTaskPreparation,
): WorkflowTaskPreparation {
  const declarations = next.evaluation.checkTexts ?? [];
  const updated = (original: TextPresentation | undefined, refreshed: TextPresentation | undefined) =>
    sameWorkflowTextSource(original, refreshed) ? refreshed : original;
  const failure =
    declarations.find((text) => text.checkKey === current.evaluation.failureCheckKey)?.failureMessageText ??
    next.evaluation.failureMessageText;
  return {
    ...current,
    evaluation: {
      ...current.evaluation,
      failureMessageText: updated(current.evaluation.failureMessageText, failure),
      checkTexts: current.evaluation.checkTexts?.map((text) => {
        const refreshed = declarations.find((item) => item.checkKey === text.checkKey);
        return {
          ...text,
          titleText: updated(text.titleText, refreshed?.titleText),
          failureMessageText: updated(text.failureMessageText, refreshed?.failureMessageText),
        };
      }),
      checkResults: current.evaluation.checkResults.map((check) => {
        const refreshed = next.evaluation.checkResults.find((item) => item.checkKey === check.checkKey);
        const declaration = declarations.find((text) => text.checkKey === check.checkKey);
        return {
          ...check,
          checkTitleText: updated(check.checkTitleText, declaration?.titleText ?? refreshed?.checkTitleText),
          failureMessageText: updated(
            check.failureMessageText,
            declaration?.failureMessageText ?? refreshed?.failureMessageText,
          ),
        };
      }),
      guides: current.evaluation.guides.map((guide) => {
        const refreshed = next.evaluation.guides.find((item) => item.guideKey === guide.guideKey);
        return {
          ...guide,
          titleText: updated(guide.titleText, refreshed?.titleText),
          instructionText: updated(guide.instructionText, refreshed?.instructionText),
        };
      }),
    },
  };
}

/** Translation refreshes do not replace instance facts, node status, routes or frozen graph JSON. */
export function refreshWorkflowTextResources(
  current: WorkflowRenderBundle,
  next: WorkflowRenderBundle,
): WorkflowRenderBundle {
  if (current.instance.id !== next.instance.id) return current;
  return {
    ...current,
    nodes: current.nodes.map((node) => {
      const refreshed = next.nodes.find((item) => item.id === node.id && item.nodeKey === node.nodeKey);
      return sameWorkflowTextSource(node.nodeTitleText, refreshed?.nodeTitleText)
        ? { ...node, nodeTitleText: refreshed?.nodeTitleText }
        : node;
    }),
  };
}

/** Recover selection semantics from frozen mode and path status after convergence updates the lifecycle reason. */
export function workflowRouteSelectionTitle(
  route: WorkflowRoute,
  source?: WorkflowNode,
  language: LanguageContext = platformLanguageContext,
): string {
  if (
    !source?.routeMode ||
    !['effective', 'closed', 'dropped', 'ineffective'].includes(route.routeStatus ?? '')
  )
    return workflowTitle(route.routeReason, language);
  const selected = route.routeStatus !== 'ineffective';
  if (source.routeMode === 'manual')
    return workflowTitle(selected ? 'manual_selected' : 'manual_unselected', language);
  if (route.defaultRoute)
    return selected
      ? workflowTitle('default_selected', language)
      : language.t('platform.workflow.default_unselected', '默认出口未启用');
  if (!selected) return workflowTitle('condition_unmatched', language);
  return route.conditionExpression?.trim()
    ? workflowTitle('condition_matched', language)
    : language.t('platform.workflow.unconditional_selected', '无条件出口生效');
}
