import { requireConfirmedConstructionPlan, type ConstructionPlanState } from './constructionPlanGuard';
import type { ConstructionDeliveryProposal, ConstructionDeliveryReceipt } from '@muyun/web-contracts';
import {
  AppError,
  AssistantCapabilityUsageError,
  AssistantOperationRejectedError,
  type AssistantCapability,
  type AssistantOperationProposal,
  type ConstructionPlanClient,
} from '@muyun/web-core';

export function createConstructionDeliveryCapabilities(
  client: ConstructionPlanClient,
  current: () => ConstructionPlanState,
  accept: (receipt: ConstructionDeliveryReceipt) => void | Promise<void>,
): AssistantCapability[] {
  const objectSchema = (properties: Record<string, unknown>) => ({
    type: 'object',
    additionalProperties: false,
    required: Object.keys(properties),
    properties,
  });
  const textSchema = { type: 'string', minLength: 1, maxLength: 120 };
  const fieldsSchema = {
    type: 'array',
    maxItems: 40,
    items: { type: 'string', minLength: 1, maxLength: 64 },
  };
  const parseObject = (input: unknown): Record<string, unknown> => {
    if (!input || typeof input !== 'object' || Array.isArray(input))
      throw new AssistantCapabilityUsageError('建设参数无效');
    return input as Record<string, unknown>;
  };
  const text = (value: unknown) => {
    if (typeof value !== 'string' || !value.trim() || value.length > 120)
      throw new AssistantCapabilityUsageError('建设参数为空或过长');
    return value.trim();
  };
  const names = (value: unknown) => {
    if (
      !Array.isArray(value) ||
      value.length > 40 ||
      value.some((item) => typeof item !== 'string' || !/^[a-z][a-zA-Z0-9_]{0,63}$/.test(item)) ||
      new Set(value).size !== value.length
    )
      throw new AssistantCapabilityUsageError('页面字段必须来自目录，且不能重复');
    return value as string[];
  };
  const presentation = (receipt: ConstructionDeliveryReceipt) => ({
    title: receipt.kind === 'PAGE' ? '页面配置已发布' : '访问入口已创建',
    lines: [
      receipt.moduleAlias,
      `依据需求第 ${receipt.planRevision} 版`,
      '请查询建设进度核实页面与入口，再按验收例子检查实际业务行为。',
    ],
  });
  async function acceptedPresentation(receipt: ConstructionDeliveryReceipt, shouldAccept: boolean) {
    const result = presentation(receipt);
    if (shouldAccept) {
      try {
        await accept(receipt);
      } catch {
        result.lines.push('配置已提交，但工作台入口刷新失败；刷新入口后再继续验证，勿重复提交。');
      }
    }
    return result;
  }
  function prepare(kind: 'PAGE' | 'ENTRY'): AssistantCapability {
    let prepared: AssistantOperationProposal | undefined;
    return {
      effect: 'read',
      descriptor: {
        code: kind === 'PAGE' ? 'construction.prepare-page' : 'construction.prepare-entry',
        description:
          kind === 'PAGE'
            ? 'Prepare a separately confirmed management page (list, form, shared detail and quick search) for an initialized object. Read actual fields first; use existing field names, include all required business fields in formFields. Replaces this construction plan’s page layout; never changes field rules. Human confirmation publishes.'
            : 'Prepare a separately confirmed menu entry for an already published construction page in the current system workbench. Does not grant business permissions. Query progress first; do not recreate an existing entry.',
        inputSchema: objectSchema({
          objectKey: textSchema,
          title: textSchema,
          ...(kind === 'PAGE'
            ? { listFields: fieldsSchema, formFields: fieldsSchema, searchFields: fieldsSchema }
            : {}),
        }),
      },
      parseInput(input) {
        const value = parseObject(input);
        return {
          kind,
          objectKey: text(value.objectKey),
          title: text(value.title),
          listFields: kind === 'PAGE' ? names(value.listFields) : [],
          formFields: kind === 'PAGE' ? names(value.formFields) : [],
          searchFields: kind === 'PAGE' ? names(value.searchFields) : [],
        };
      },
      async execute(input, context) {
        const before = requireConfirmedConstructionPlan(current);
        const plan = before.saved;
        const preview = await client.previewDelivery(plan.planId, {
          ...(input as Omit<ConstructionDeliveryProposal, 'planRevision'>),
          planRevision: plan.revision,
        });
        const stable = structuredClone(preview);
        const requestId = crypto.randomUUID();
        const { isCurrent } = before;
        context.commitInternalState(() => {
          prepared = {
            modelSummary:
              kind === 'PAGE'
                ? '发布已预检的列表、表单和查询页面配置，不修改业务字段或规则。'
                : '创建已发布页面的工作台入口，不授予额外业务权限。',
            confirmLabel: kind === 'PAGE' ? '确认发布页面' : '确认创建入口',
            expiresAt: Date.now() + 5 * 60_000,
            isCurrent,
            presentation: {
              title: kind === 'PAGE' ? '发布业务页面' : '创建工作台入口',
              lines: [stable.moduleAlias, ...stable.lines],
            },
            async execute() {
              if (!isCurrent()) throw new AssistantOperationRejectedError('需求已变化，请重新预检');
              try {
                const receipt = await client.publishDelivery(plan.planId, {
                  requestId,
                  proposal: stable.proposal,
                  fingerprint: stable.fingerprint,
                });
                return acceptedPresentation(receipt, isCurrent());
              } catch (error) {
                if (error instanceof AppError && [400, 401, 403, 404, 409, 422].includes(error.status ?? 0))
                  throw new AssistantOperationRejectedError(error.message);
                throw error;
              }
            },
            async lookup() {
              const receipt = await client.delivery(plan.planId, requestId);
              if (!receipt) return undefined;
              return acceptedPresentation(receipt, isCurrent());
            },
          };
        });
        return { awaitingHumanConfirmation: true };
      },
      propose() {
        if (!prepared) throw new AssistantCapabilityUsageError('请先预检建设节点');
        return prepared;
      },
    };
  }
  let acceptance: AssistantOperationProposal | undefined;
  const acceptanceCapability: AssistantCapability = {
    effect: 'read',
    descriptor: {
      code: 'construction.prepare-acceptance',
      description:
        'Present human business acceptance checklist after actual record entry, query and detail verification. Do not infer acceptance from publication receipts. Resolve requirements discrepancies and unsupported rules first. Only an explicit human click records acceptance of the current baseline.',
      inputSchema: objectSchema({ objectKey: textSchema }),
    },
    parseInput: (input) => ({ objectKey: text(parseObject(input).objectKey) }),
    async execute(input, context) {
      const before = requireConfirmedConstructionPlan(current);
      const planId = before.saved.planId;
      const preview = await client.previewAcceptance(planId, (input as { objectKey: string }).objectKey);
      const command = {
        requestId: crypto.randomUUID(),
        objectKey: preview.objectKey,
        fingerprint: preview.fingerprint,
      };
      const { isCurrent } = before;
      const result = {
        title: '已记录人工验收通过',
        lines: ['仅适用于已核对的需求与配置基线；后续变更需要重新验收。'],
      };
      context.commitInternalState(() => {
        acceptance = {
          modelSummary:
            '记录用户对当前需求及配置基线的人工验收。必须先实际核对，不自动证明业务要求已经实现。',
          confirmLabel: '我已核对并确认验收通过',
          expiresAt: Date.now() + 5 * 60_000,
          isCurrent,
          presentation: {
            title: '业务验收确认',
            lines: [
              `需求第 ${preview.planRevision} 版`,
              '请先实际录入、查询并检查业务结果，逐项核对下方完整清单。确认表示你已核对通过，不是让系统自动证明所有需求。',
            ],
            details: { title: '查看完整验收清单', lines: preview.checks },
          },
          async execute() {
            if (!isCurrent()) throw new AssistantOperationRejectedError('验收上下文已变化');
            try {
              await client.confirmAcceptance(planId, command);
              return result;
            } catch (error) {
              if (error instanceof AppError && [400, 401, 403, 404, 409, 422].includes(error.status ?? 0))
                throw new AssistantOperationRejectedError(error.message);
              throw error;
            }
          },
          async lookup() {
            return (await client.acceptance(planId, command.requestId)) ? result : undefined;
          },
        };
      });
      return { awaitingHumanConfirmation: true };
    },
    propose() {
      if (!acceptance) throw new AssistantCapabilityUsageError('请先核对验收范围');
      return acceptance;
    },
  };
  return [
    prepare('PAGE'),
    prepare('ENTRY'),
    acceptanceCapability,
    {
      effect: 'read',
      descriptor: {
        code: 'construction.progress',
        description:
          'Read actual publication, visible entry, runtime and pending acceptance for an initialized object. Restores progress after a page reload. A publication receipt never proves business acceptance. Use returned menuId with workbench navigation to test the actual page.',
        inputSchema: objectSchema({ objectKey: textSchema }),
      },
      parseInput: (input) => ({ objectKey: text(parseObject(input).objectKey) }),
      async execute(input) {
        const plan = current().saved;
        if (!plan) throw new AssistantCapabilityUsageError('请先恢复已确认方案');
        return client.progress(plan.planId, (input as { objectKey: string }).objectKey);
      },
      present: (value) => {
        const progress = value as Awaited<ReturnType<ConstructionPlanClient['progress']>>;
        return {
          title: '应用建设进度',
          lines: [
            progress.moduleAlias,
            `运行态：${progress.runtimeStatus}`,
            `页面：${progress.pagePublished ? '已发布' : '未完成'}；入口：${progress.entryVisible ? '当前用户可见' : '不可见'}`,
            `人工验收：${progress.acceptanceConfirmed ? '当前基线已确认通过' : '待核对'}`,
            ...progress.remainingWork,
          ],
        };
      },
    },
  ];
}
