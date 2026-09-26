import { createConstructionDeliveryCapabilities } from './constructionDelivery';
import { createConstructionFieldCapabilities } from './constructionFields';
import { createConstructionInitializationCapabilities } from './constructionInitialization';
import { shallowRef } from 'vue';
import type {
  ConstructionDeliveryReceipt,
  ConstructionTask,
  ConstructionPlanContent,
  ConstructionPlanSnapshot,
  ConstructionPlanSummary,
} from '@muyun/web-contracts';
import {
  AppError,
  AssistantCapabilityUsageError,
  AssistantOperationRejectedError,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
  type AssistantCapability,
  type AssistantOperationProposal,
  type ConstructionPlanClient,
} from '@muyun/web-core';

const listKeys = [
  'inScope',
  'outOfScope',
  'relationships',
  'rules',
  'questions',
  'assumptions',
  'acceptanceExamples',
] as const;
export const planSections: Record<(typeof listKeys)[number], string> = {
  inScope: '本期范围',
  outOfScope: '暂不建设',
  relationships: '对象关系',
  rules: '业务规则',
  questions: '未决问题',
  assumptions: '待验证假设',
  acceptanceExamples: '验收例子',
};
const textSchema = (maxLength: number) => ({ type: 'string', minLength: 1, maxLength });
const itemSchema = (properties: Record<string, unknown>) => ({
  type: 'object',
  additionalProperties: false,
  required: Object.keys(properties),
  properties,
});
const arraySchema = (items: unknown) => ({ type: 'array', maxItems: 16, items });
export const constructionPlanContentSchema = itemSchema({
  title: textSchema(120),
  goal: textSchema(1500),
  ...Object.fromEntries(listKeys.map((key) => [key, arraySchema(textSchema(500))])),
  objects: arraySchema(
    itemSchema({
      key: { ...textSchema(64), pattern: '^[a-z][a-z0-9_-]*$' },
      name: textSchema(120),
      purpose: textSchema(500),
    }),
  ),
  requirements: {
    type: 'array',
    maxItems: 64,
    items: itemSchema({
      section: { type: 'string', enum: ['SCOPE', 'RULE', 'RELATION'] },
      index: { type: 'integer', minimum: 0, maximum: 15 },
      objectKey: textSchema(64),
      mode: { type: 'string', enum: ['FIELD', 'REQUIRED', 'UNIQUE', 'MANUAL', 'UNSUPPORTED'] },
      fieldName: { type: 'string', maxLength: 64 },
      explanation: textSchema(500),
    }),
  },
  decisions: arraySchema(
    itemSchema({
      statement: textSchema(500),
      source: { type: 'string', enum: ['USER_REQUIREMENT', 'RECOMMENDATION'] },
    }),
  ),
});
function fail(message: string): never {
  throw new AssistantCapabilityUsageError(message);
}
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail('方案格式无效');
  return value as Record<string, unknown>;
}
function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max) fail('方案文本为空或超出长度限制');
  return value.trim();
}
function list<T>(value: unknown, parse: (entry: unknown) => T): T[] {
  if (!Array.isArray(value) || value.length > 16) fail('每组方案条目最多 16 项');
  return value.map(parse);
}
export function parseConstructionPlan(value: unknown): ConstructionPlanContent {
  const input = record(value);
  if (Object.keys(input).some((key) => !Object.hasOwn(constructionPlanContentSchema.properties, key)))
    fail('方案包含不支持的属性');
  const content = {
    title: text(input.title, 120),
    goal: text(input.goal, 1500),
    ...Object.fromEntries(listKeys.map((key) => [key, list(input[key], (item) => text(item, 500))])),
    objects: list(input.objects, (value) => {
      const object = record(value);
      const key = text(object.key, 64);
      if (!/^[a-z][a-z0-9_-]*$/.test(key)) fail('业务对象标识格式无效');
      return { key, name: text(object.name, 120), purpose: text(object.purpose, 500) };
    }),
    decisions: list(input.decisions, (value) => {
      const decision = record(value);
      if (decision.source !== 'USER_REQUIREMENT' && decision.source !== 'RECOMMENDATION')
        fail('需求来源无效');
      return { statement: text(decision.statement, 500), source: decision.source };
    }),
  } as ConstructionPlanContent;
  if (input.requirements !== undefined) {
    if (!Array.isArray(input.requirements) || input.requirements.length > 64) fail('需求兑现项最多 64 项');
    const bindings = new Set<string>();
    content.requirements = input.requirements.map((entry) => {
      const item = record(entry);
      if (
        !['SCOPE', 'RULE', 'RELATION'].includes(String(item.section)) ||
        !['FIELD', 'REQUIRED', 'UNIQUE', 'MANUAL', 'UNSUPPORTED'].includes(String(item.mode))
      )
        fail('需求兑现方式无效');
      const section = item.section as 'SCOPE' | 'RULE' | 'RELATION';
      const mode = item.mode as 'FIELD' | 'REQUIRED' | 'UNIQUE' | 'MANUAL' | 'UNSUPPORTED';
      const source =
        section === 'SCOPE' ? content.inScope : section === 'RULE' ? content.rules : content.relationships;
      if (!Number.isInteger(item.index) || Number(item.index) < 0 || Number(item.index) >= source.length)
        fail('兑现项须引用本版要求');
      const objectKey = text(item.objectKey, 64);
      if (!content.objects.some((object) => object.key === objectKey)) fail('兑现项业务对象不存在');
      const fieldName = typeof item.fieldName === 'string' ? item.fieldName.trim() : '';
      const field = ['FIELD', 'REQUIRED', 'UNIQUE'].includes(mode);
      if (field ? !/^[a-z][a-zA-Z0-9_]{0,63}$/.test(fieldName) : Boolean(fieldName)) fail('兑现检查字段无效');
      if (section === 'RELATION' && mode !== 'UNSUPPORTED') fail('对象关联尚不支持，须商定后续范围');
      const key = [section, item.index, objectKey, mode, fieldName].join(':');
      if (bindings.has(key)) fail('需求兑现项不能重复');
      bindings.add(key);
      return {
        section,
        index: Number(item.index),
        objectKey,
        mode,
        fieldName,
        explanation: text(item.explanation, 500),
      };
    });
  }
  if (new Set(content.objects.map((object) => object.key)).size !== content.objects.length)
    fail('业务对象标识不能重复');
  if (new TextEncoder().encode(JSON.stringify(content)).length > 32 * 1024) fail('方案内容过长');
  return content;
}
export function presentConstructionPlan(content: ConstructionPlanContent) {
  return {
    title: content.title,
    lines: [
      content.goal,
      ...Object.entries(planSections).flatMap(([key, label]) =>
        content[key as keyof typeof planSections].map((item) => `${label}：${item}`),
      ),
      ...content.objects.map((object) => `业务对象：${object.name} — ${object.purpose}`),
      ...content.decisions.map(
        (decision) =>
          `${decision.source === 'USER_REQUIREMENT' ? '用户要求' : '建议'}：${decision.statement}`,
      ),
      ...(content.requirements ?? []).map((item) => {
        const source =
          item.section === 'SCOPE'
            ? content.inScope
            : item.section === 'RULE'
              ? content.rules
              : content.relationships;
        const mode = {
          FIELD: '核对字段存在',
          REQUIRED: '系统必填',
          UNIQUE: '系统防重复',
          MANUAL: '人工处理并核验',
          UNSUPPORTED: '暂不支持',
        }[item.mode];
        return `兑现方式：${source[item.index]} — ${mode}；${item.explanation}`;
      }),
      '本次仅确认需求范围，不创建或发布业务配置；已有建设结果保留。',
    ],
  };
}

export function createConstructionPlanSession(
  client: ConstructionPlanClient,
  identity: () => string,
  initializationAvailable: () => boolean = () => false,
  onDelivered: (receipt: ConstructionDeliveryReceipt) => Promise<void> = async () => {},
) {
  const state = shallowRef<{
    planId?: string;
    saved?: ConstructionPlanSnapshot;
    candidate?: ConstructionPlanContent;
    generation: number;
    reviewRequired?: boolean;
  }>({ generation: 0 });
  const savedPlans = shallowRef<ConstructionPlanSummary[]>([]);
  const manualEditing = shallowRef(false);
  let owner = identity();
  let unresolved = false;
  let submitting = false;
  const recovery = shallowRef<() => Promise<unknown>>();
  function syncIdentity() {
    if (owner === identity()) return;
    owner = identity();
    manualEditing.value = false;
    unresolved = false;
    submitting = false;
    recovery.value = undefined;
    savedPlans.value = [];
    state.value = { generation: state.value.generation + 1 };
  }
  function current() {
    syncIdentity();
    return state.value;
  }
  function dirty() {
    const value = current();
    return (
      !!value.candidate &&
      JSON.stringify(parseConstructionPlan(value.candidate)) !==
        JSON.stringify(value.saved && parseConstructionPlan(value.saved.content))
    );
  }
  function beginManualEdit() {
    syncIdentity();
    if (unresolved) fail('上次确认结果尚未查明，请先查询结果');
    manualEditing.value = true;
    state.value = { ...state.value, generation: state.value.generation + 1 };
  }
  function cancelManualEdit() {
    manualEditing.value = false;
  }
  function edit(value: unknown) {
    syncIdentity();
    if (unresolved) fail('上次确认结果尚未查明，请先查询结果');
    const content = parseConstructionPlan(value);
    manualEditing.value = false;
    state.value = {
      ...state.value,
      planId: state.value.planId ?? crypto.randomUUID().replaceAll('-', ''),
      candidate: content,
      reviewRequired: false,
      generation: state.value.generation + 1,
    };
  }
  function editManually(value: unknown) {
    const before = current().candidate;
    const content = parseConstructionPlan(value);
    const semanticChange =
      before &&
      (['goal', 'inScope', 'acceptanceExamples'] as const).some(
        (key) => JSON.stringify(before[key]) !== JSON.stringify(content[key]),
      );
    const reviewRequired =
      !!state.value.reviewRequired ||
      !!(
        semanticChange &&
        (content.questions.length ||
          content.assumptions.length ||
          content.rules.length ||
          content.decisions.length ||
          content.objects.length ||
          content.relationships.length)
      );
    edit(content);
    state.value = { ...state.value, reviewRequired };
  }
  function newPlan() {
    syncIdentity();
    if (unresolved) fail('上次确认结果尚未查明，请先查询结果');
    manualEditing.value = false;
    state.value = { generation: state.value.generation + 1 };
  }
  async function listSaved() {
    syncIdentity();
    const scope = owner;
    const result = await client.list();
    syncIdentity();
    if (scope !== owner) fail('登录身份已变化，请重新读取');
    savedPlans.value = result;
    return result;
  }
  async function load(id: string) {
    const before = current();
    const scope = owner;
    if (dirty() || unresolved || manualEditing.value) fail('请先确认当前候选，或由用户放弃候选后再恢复方案');
    const result = await client.read(id);
    syncIdentity();
    if (scope !== owner || state.value.generation !== before.generation) fail('当前方案已变化，请重新恢复');
    return { result, generation: before.generation, scope };
  }
  function applyLoaded(loaded: Awaited<ReturnType<typeof load>>) {
    syncIdentity();
    if (loaded.scope !== owner || loaded.generation !== state.value.generation)
      fail('当前方案已变化，请重新恢复');
    state.value = {
      planId: loaded.result.planId,
      saved: loaded.result,
      candidate: structuredClone(loaded.result.content),
      generation: state.value.generation + 1,
    };
  }
  async function restore(id: string) {
    applyLoaded(await load(id));
  }
  async function history() {
    const before = current();
    const scope = owner;
    if (!before.planId || !before.saved) fail('请先恢复或确认方案');
    const result = await client.history(before.planId);
    current();
    if (owner !== scope || before.generation !== state.value.generation)
      fail('当前方案已变化，请重新读取历史');
    return result;
  }
  function changes() {
    const value = current();
    if (!value.candidate) return [];
    const labels = {
      title: '名称',
      goal: '业务目标',
      ...planSections,
      objects: '业务对象',
      decisions: '需求与建议',
      requirements: '逐项兑现方式',
    };
    return Object.entries(labels)
      .filter(
        ([key]) =>
          JSON.stringify(value.candidate?.[key as keyof ConstructionPlanContent]) !==
          JSON.stringify(value.saved?.content[key as keyof ConstructionPlanContent]),
      )
      .map(([, label]) => label);
  }
  function prepare(): AssistantOperationProposal {
    const before = current();
    const scope = owner;
    if (manualEditing.value) fail('请先完成或取消人工修改');
    if (before.reviewRequired) fail('目标或范围已修改，请先核对关联问题、假设和规则，再准备确认');
    if (submitting) fail('正在提交确认，请等待结果');
    if (unresolved) fail('上次确认结果尚未查明，请先查询结果');
    if (!before.planId || !before.candidate || !dirty()) fail('请先整理或修改需求方案');
    if (!before.candidate.inScope.length || !before.candidate.acceptanceExamples.length)
      fail('确认前须明确本期范围和至少一个验收例子');
    const planId = before.planId;
    const content = structuredClone(before.candidate);
    const bindings = content.requirements ?? [];
    const covered = new Set(bindings.map((item) => `${item.section}:${item.index}`)).size;
    const missing = content.inScope.length + content.rules.length + content.relationships.length - covered;
    const unsupported = bindings.filter((item) => item.mode === 'UNSUPPORTED');
    const manual = bindings.filter((item) => item.mode === 'MANUAL');
    const requestId = crypto.randomUUID();
    const expectedRevision = before.saved?.revision ?? 0;
    const isCurrent = () => {
      current();
      return owner === scope && state.value.generation === before.generation && !submitting;
    };
    function accepted(result: ConstructionPlanSnapshot) {
      current();
      if (owner === scope && state.value.generation === before.generation) {
        unresolved = false;
        recovery.value = undefined;
        state.value = {
          planId,
          candidate: structuredClone(result.content),
          saved: result,
          generation: before.generation + 1,
        };
      }
      return {
        title: '需求方案已确认',
        lines: [
          `${result.content.title} · 第 ${result.revision} 版`,
          result.initializations.length
            ? '已有模块初始化记录；后续配置变更仍须独立审阅和授权。'
            : '尚未建设。后续配置变更仍须独立审阅和授权。',
        ],
      };
    }
    const proposal: AssistantOperationProposal = {
      confirmLabel: '确认本期方案',
      presentation: {
        lines: [
          content.title,
          `目标：${content.goal.length > 180 ? content.goal.slice(0, 180) + '…' : content.goal}`,
          `本版变化：${changes().join('、')}`,
          '确认只保存需求，刷新后可找回；不会创建应用或保存订单。',
          `兑现核对：${missing} 条尚未对应，${unsupported.length} 项暂不支持，${manual.length} 项需人工处理或试用核验。未对应或不支持项会阻止建设。`,
          ...manual.slice(0, 3).map((item) => `人工承担：${item.explanation.slice(0, 140)}`),
          ...(manual.length > 3 ? [`另有 ${manual.length - 3} 项人工事项，请展开完整范围核对。`] : []),
          ...unsupported.slice(0, 3).map((item) => `待商定：${item.explanation.slice(0, 140)}`),
          content.relationships.length
            ? '方案含对象关联，当前对话建设暂不能完整实现；须先商定分期范围，不能直接当作可交付承诺。'
            : '下一步再核对可实现范围，逐步建设可用页面。',
        ],
        details: { title: '查看完整范围、规则和验收例子', lines: presentConstructionPlan(content).lines },
        title: `确认本期方案 · 第 ${expectedRevision + 1} 版`,
      },
      expiresAt: Date.now() + 5 * 60_000,
      isCurrent,
      async execute() {
        if (!isCurrent()) throw new AssistantOperationRejectedError('方案已变化，请重新审阅');
        unresolved = true;
        submitting = true;
        recovery.value = () => proposal.lookup();
        try {
          return accepted(await client.confirm(planId, { requestId, expectedRevision, content }));
        } catch (error) {
          if (error instanceof AppError && [400, 401, 403, 404, 409, 422].includes(error.status ?? 0)) {
            if (owner === scope) {
              unresolved = false;
              recovery.value = undefined;
            }
            throw new AssistantOperationRejectedError(error.message);
          }
          throw error;
        } finally {
          if (owner === scope) submitting = false;
        }
      },
      async lookup() {
        current();
        if (owner !== scope) fail('登录身份已变化，请重新读取');
        const result = await client.confirmation(planId, requestId);
        return result ? accepted(result) : undefined;
      },
    };
    return proposal;
  }
  function facts() {
    const value = current();
    return {
      generation: value.generation,
      task: currentTask(),
      planId: value.planId,
      revision: value.saved?.revision ?? 0,
      candidate: value.candidate,
      dirty: dirty(),
      persistence: unresolved
        ? 'UNKNOWN'
        : dirty()
          ? 'UNSAVED_CANDIDATE'
          : value.saved
            ? 'SAVED_REQUIREMENTS'
            : 'NO_PLAN',
      persistenceExplanation: unresolved
        ? '保存结果尚未确定，请先查询结果，不要重复保存。'
        : dirty()
          ? '当前修改尚未保存，刷新会丢失；已有已确认版本仍保留。'
          : value.saved
            ? '已保存的是需求方案，不代表应用可用或业务单据已保存。'
            : '尚无已保存方案。',
      deliveryScope: {
        supported: ['独立登记表', '普通字段及必填、唯一约束', '列表、表单、详情及查询'],
        unsupported: ['对象关联与多行明细', '跨表汇总和自动余额计算', '自动状态流转', '自动授权'],
        relationshipsNeedScopeDecision: Boolean(value.candidate?.relationships.length),
      },
      manualEditing: manualEditing.value,
      reviewRequired: !!value.reviewRequired,
      confirmationResultUnknown: unresolved,
      constructionStatus: value.saved?.constructionStatus ?? 'NOT_STARTED',
      initializations: value.saved?.initializations ?? [],
      fieldChanges: value.saved?.fieldChanges ?? [],
      deliveries: value.saved?.deliveries ?? [],
      confirmationIsNotPublication: true,
      initializationAvailable: initializationAvailable(),
    };
  }
  const fieldCapabilities = createConstructionFieldCapabilities(
    client,
    () => {
      const value = current();
      return {
        saved: value.saved,
        generation: value.generation,
        dirty: dirty() || !!value.reviewRequired,
        editing: manualEditing.value,
      };
    },
    (result, invalidate = true) => {
      const value = current();
      if (!value.saved) return;
      state.value = {
        ...value,
        saved: {
          ...value.saved,
          fieldChanges: [
            ...value.saved.fieldChanges.filter((receipt) => receipt.requestId !== result.receipt.requestId),
            result.receipt,
          ],
        },
        generation: value.generation + (invalidate ? 1 : 0),
      };
    },
  );
  const deliveryCapabilities = createConstructionDeliveryCapabilities(
    client,
    () => {
      const value = current();
      return {
        saved: value.saved,
        generation: value.generation,
        dirty: dirty() || !!value.reviewRequired,
        editing: manualEditing.value,
      };
    },
    async (receipt) => {
      const value = current();
      if (!value.saved) return;
      state.value = {
        ...value,
        saved: {
          ...value.saved,
          deliveries: [
            ...value.saved.deliveries.filter((item) => item.requestId !== receipt.requestId),
            receipt,
          ],
        },
        generation: value.generation + 1,
      };
      await onDelivered(receipt);
    },
  );
  const task = shallowRef<ConstructionTask>();
  let taskGeneration = -1;
  async function readTask() {
    const before = current();
    const scope = owner;
    if (!before.saved || dirty() || before.reviewRequired) fail('请先确认最新需求及兑现方式');
    const result = await client.task(before.saved.planId);
    current();
    if (owner !== scope || state.value.generation !== before.generation) fail('方案已变化，请重新读取任务');
    if (result.planRevision !== before.saved.revision) fail('需求已在其他会话更新，请恢复最新方案后继续');
    task.value = result;
    taskGeneration = before.generation;
    return result;
  }
  function currentTask() {
    return taskGeneration === current().generation ? task.value : undefined;
  }
  function withContinuation(capability: AssistantCapability): AssistantCapability {
    if (
      !initializationAvailable() ||
      !capability.propose ||
      capability.descriptor.code === 'construction.prepare-acceptance'
    )
      return capability;
    const propose = capability.propose;
    return {
      ...capability,
      propose(output) {
        const before = current();
        const planId = before.planId;
        const revision =
          (before.saved?.revision ?? 0) +
          (capability.descriptor.code === 'construction.prepare-confirmation' ? 1 : 0);
        return {
          ...propose(output),
          continuation: {
            message:
              '平台续接：上一项已经确认成功。先读取 construction.task，依据真实任务进度核实结果并准备下一步；不要重复提交已完成节点。新的写入仅准备确认，遇到范围取舍或人工业务核验时停下询问用户。',
            isCurrent: () =>
              current().saved?.planId === planId &&
              current().saved?.revision === revision &&
              !dirty() &&
              !manualEditing.value,
          },
        };
      },
    };
  }
  function capabilities(): AssistantCapability[] {
    const empty = (
      code: string,
      description: string,
      execute: () => Promise<unknown>,
    ): AssistantCapability => ({
      effect: 'read',
      descriptor: { code, description, inputSchema: emptyAssistantCapabilityInputSchema() },
      parseInput: parseEmptyAssistantCapabilityInput,
      execute,
    });
    const result: AssistantCapability[] = [
      ...(initializationAvailable() ? [...fieldCapabilities, ...deliveryCapabilities] : []),
      ...(initializationAvailable()
        ? createConstructionInitializationCapabilities(
            client,
            () => {
              const value = current();
              return {
                saved: value.saved,
                generation: value.generation,
                dirty: dirty() || !!value.reviewRequired,
                editing: manualEditing.value,
              };
            },
            (result, invalidate = true) => {
              const value = current();
              if (!value.saved) return;
              state.value = {
                ...value,
                saved: {
                  ...value.saved,
                  constructionStatus: 'INITIALIZED',
                  initializations: [
                    ...value.saved.initializations.filter(
                      (item) => item.objectKey !== result.receipt.objectKey,
                    ),
                    result.receipt,
                  ],
                },
                generation: value.generation + (invalidate ? 1 : 0),
              };
            },
          )
        : []),
      ...(initializationAvailable()
        ? [
            {
              ...empty(
                'construction.task',
                'Read the durable construction task, verified requirement evidence and next action. Always read this after confirmation or restoring a plan; do not repeat completed writes.',
                readTask,
              ),
              present: (result: unknown) => ({
                title: '接下来要做的事',
                lines: (result as ConstructionTask).objects.map(
                  (item) => `${item.title}：${item.nextAction}`,
                ),
              }),
            },
          ]
        : []),
      empty(
        'construction.describe',
        'Read the active requirements plan. Confirming requirements never creates or publishes business configuration.',
        async () => facts(),
      ),
      empty(
        'construction.history',
        'List immutable confirmed requirements revisions for the active plan. This never restores an execution approval.',
        async () =>
          (await history()).map(({ revision, confirmedAt, content }) => ({
            revision,
            confirmedAt,
            title: content.title,
          })),
      ),
      empty(
        'construction.find-saved',
        'List current user saved requirements plans before restoring one.',
        listSaved,
      ),
      {
        effect: 'configuration-draft',
        descriptor: {
          code: 'construction.propose',
          description:
            'Replace the local requirements candidate after discussing scope. If reviewRequired, reconcile questions, assumptions, rules and decisions against the latest human edits first; preserve unanswered questions and never infer user consent. Preserve decisions; distinguish user requirements from recommendations. No persistence or business configuration changes.',
          inputSchema: itemSchema({
            generation: { type: 'integer', minimum: 0 },
            content: constructionPlanContentSchema,
          }),
        },
        parseInput(input) {
          const value = record(input);
          return { generation: value.generation, content: parseConstructionPlan(value.content) };
        },
        async execute(input, context) {
          const value = input as { generation: number; content: ConstructionPlanContent };
          if (manualEditing.value) fail('请先完成或取消人工修改');
          if (value.generation !== current().generation)
            fail('候选已变化，请先重新读取 construction.describe');
          context.applyEffect(() => edit(value.content));
          return facts();
        },
        present: () => ({
          title: '需求方案已更新，尚未保存',
          lines: [`本版变化：${changes().join('、')}。可在业务建设方案中查看详情。`],
        }),
      },
      {
        ...empty(
          'construction.prepare-confirmation',
          'Prepare a human-only confirmation card for the current requirements candidate. This does not approve implementation.',
          async () => ({ awaitingHumanConfirmation: true }),
        ),
        propose: () => prepare(),
      },
      {
        effect: 'configuration-draft',
        descriptor: {
          code: 'construction.restore',
          description:
            'Resume a saved requirements plan from find-saved. Does not restore approvals or overwrite a changed candidate.',
          inputSchema: itemSchema({ planId: textSchema(32) }),
        },
        parseInput(input) {
          return { planId: text(record(input).planId, 32) };
        },
        async execute(input, context) {
          const { planId } = input as { planId: string };
          if (!savedPlans.value.some((plan) => plan.planId === planId)) fail('请先查询已保存的方案');
          const loaded = await load(planId);
          context.applyEffect(() => applyLoaded(loaded));
          return facts();
        },
      },
    ];
    return result.map(withContinuation);
  }
  return {
    task,
    currentTask,
    readTask,
    state,
    manualEditing,
    beginManualEdit,
    cancelManualEdit,
    savedPlans,
    recovery,
    history,
    changes,
    current,
    dirty,
    edit,
    editManually,
    newPlan,
    listSaved,
    restore,
    prepare,
    capabilities,
    async progress(objectKey: string) {
      const before = current();
      if (!before.saved) fail('请先恢复已确认方案');
      const result = await client.progress(before.saved!.planId, objectKey);
      const after = current();
      if (after.saved?.planId !== before.saved!.planId || after.generation !== before.generation)
        fail('建设上下文已变化，请重新查询');
      return result;
    },
    facts,
  };
}
export type ConstructionPlanSession = ReturnType<typeof createConstructionPlanSession>;
