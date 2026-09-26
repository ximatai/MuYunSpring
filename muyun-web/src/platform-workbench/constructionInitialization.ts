import { requireConfirmedConstructionPlan, type ConstructionPlanState } from './constructionPlanGuard';
import type {
  ConstructionInitializationPreview,
  ConstructionInitializationResult,
} from '@muyun/web-contracts';
import {
  AppError,
  AssistantCapabilityUsageError,
  AssistantOperationRejectedError,
  type AssistantCapability,
  type AssistantOperationProposal,
  type ConstructionPlanClient,
} from '@muyun/web-core';

/** Local confirmation handles own their frozen previews; no publication token enters model messages. */
export function createConstructionInitializationCapabilities(
  client: ConstructionPlanClient,
  current: () => ConstructionPlanState,
  accept: (result: ConstructionInitializationResult, invalidate?: boolean) => void,
): AssistantCapability[] {
  let prepared: AssistantOperationProposal | undefined;
  function objectInput(input: unknown) {
    if (!input || typeof input !== 'object' || Array.isArray(input))
      throw new AssistantCapabilityUsageError('初始化参数无效');
    return input as Record<string, unknown>;
  }
  function string(input: Record<string, unknown>, key: string, max: number) {
    const value = input[key];
    if (typeof value !== 'string' || !value.trim() || value.length > max)
      throw new AssistantCapabilityUsageError(`初始化参数 ${key} 无效`);
    return value.trim();
  }
  function presentation(preview: ConstructionInitializationPreview) {
    return {
      title: `准备建设：${preview.moduleTitle}`,
      lines: [
        `${preview.createsApplication ? '新建独立应用' : '在现有应用中新增登记表'}：${preview.applicationTitle}`,
        `登记表：${preview.moduleTitle}`,
        preview.createsApplication
          ? '不接管或覆盖已有应用，也不会替你录入业务单据。'
          : '保留现有内容；本次只新增这张登记表，不授予其他人权限。',
        '确认后先建立空的登记表，还不能记单；接下来需要配置要填的内容、可操作的页面和入口。',
      ],
      details: {
        title: '查看建设与存储详情',
        lines: [
          `依据已确认方案第 ${preview.proposal.planRevision} 版`,
          `模块：${preview.moduleAlias}`,
          ...preview.remainingWork,
          `存储变更：新建 ${preview.schemaName}.${preview.tableName}`,
        ],
      },
    };
  }
  function resultPresentation(result: ConstructionInitializationResult) {
    return {
      title: '模块初始化配置已提交',
      lines: [
        `模块：${result.receipt.moduleAlias}`,
        result.runtime
          ? `运行态：${result.runtime.status}${result.runtime.failureMessage ? `（${result.runtime.failureMessage}）` : ''}`
          : '运行态激活尚待核实，请查询建设状态。',
        '业务字段、关系、页面、菜单和业务验收仍须继续建设。',
      ],
    };
  }
  return [
    {
      effect: 'read',
      descriptor: {
        code: 'construction.prepare-initialization',
        description:
          'Preview initializing one confirmed business object as a new dynamic module and MAIN entity. May create a business application. System configuration identity required. Returns a human-only confirmation; no fields, pages or menus are published. Never reuse existing module aliases.',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['objectKey', 'applicationAlias', 'applicationTitle', 'moduleName'],
          properties: {
            objectKey: { type: 'string', minLength: 1, maxLength: 64 },
            applicationAlias: { type: 'string', pattern: '^[a-z][a-z0-9_]*$', maxLength: 24 },
            applicationTitle: { type: 'string', minLength: 1, maxLength: 120 },
            moduleName: { type: 'string', pattern: '^[a-z][a-z0-9_]*$', maxLength: 24 },
          },
        },
      },
      parseInput(input) {
        const value = objectInput(input);
        return {
          objectKey: string(value, 'objectKey', 64),
          applicationAlias: string(value, 'applicationAlias', 24),
          applicationTitle: string(value, 'applicationTitle', 120),
          moduleName: string(value, 'moduleName', 24),
        };
      },
      async execute(input, context) {
        const before = requireConfirmedConstructionPlan(current);
        const proposal = {
          ...(input as {
            objectKey: string;
            applicationAlias: string;
            applicationTitle: string;
            moduleName: string;
          }),
          planRevision: before.saved.revision,
        };
        if (!before.saved.content.objects.some((object) => object.key === proposal.objectKey))
          throw new AssistantCapabilityUsageError('对象不在已确认方案中');
        const preview = await client.previewInitialization(before.saved.planId, proposal);
        const stable = structuredClone(preview);
        const requestId = crypto.randomUUID();
        const { isCurrent } = before;
        context.commitInternalState(() => {
          prepared = {
            modelSummary: '建立空登记表；不覆盖现有应用，不录入业务单据。字段、页面及入口仍需后续分别确认。',
            presentation: presentation(stable),
            confirmLabel: '确认建立空登记表',
            expiresAt: Date.now() + 5 * 60_000,
            isCurrent,
            async execute() {
              if (!isCurrent()) throw new AssistantOperationRejectedError('方案已变化，请重新预检');
              try {
                const result = await client.initialize(before.saved.planId, {
                  requestId,
                  proposal: stable.proposal,
                  fingerprint: stable.fingerprint,
                });
                if (isCurrent()) accept(result);
                return resultPresentation(result);
              } catch (error) {
                if (error instanceof AppError && [400, 401, 403, 404, 409, 422].includes(error.status ?? 0))
                  throw new AssistantOperationRejectedError(error.message);
                throw error;
              }
            },
            async lookup() {
              const result = await client.initialization(before.saved.planId, proposal.objectKey);
              if (!result || result.receipt.requestId !== requestId) return undefined;
              if (isCurrent()) accept(result);
              return resultPresentation(result);
            },
          };
        });
        return {
          awaitingHumanConfirmation: true,
          moduleTitle: preview.moduleTitle,
          moduleAlias: preview.moduleAlias,
          remainingWork: preview.remainingWork,
        };
      },
      propose() {
        if (!prepared) throw new AssistantCapabilityUsageError('请先完成初始化预检');
        return prepared;
      },
    },
    {
      effect: 'read',
      descriptor: {
        code: 'construction.initialization-status',
        description:
          'Read the committed initialization receipt and actual runtime activation state for a business object. Initialization is not application completion.',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['objectKey'],
          properties: { objectKey: { type: 'string', minLength: 1, maxLength: 64 } },
        },
      },
      parseInput(input) {
        return { objectKey: string(objectInput(input), 'objectKey', 64) };
      },
      async execute(input, context) {
        const saved = current().saved;
        if (!saved) throw new AssistantCapabilityUsageError('请先确认或恢复需求方案');
        const result = await client.initialization(saved.planId, (input as { objectKey: string }).objectKey);
        if (result) context.commitInternalState(() => accept(result, false));
        return result ?? { status: 'NOT_INITIALIZED' };
      },
    },
  ];
}
