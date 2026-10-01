import {
  constructionObjectKeySchema,
  requireConfirmedConstructionPlan,
  type ConstructionPlanState,
} from './constructionPlanGuard';
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
  onAccepted: () => Promise<void> = async () => {},
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
  const children = (value: unknown) => {
    if (value === undefined) return {};
    const input = parseObject(value);
    if (
      Object.keys(input).length > 16 ||
      Object.keys(input).some((alias) => !/^[a-z][a-z0-9_]{0,62}$/.test(alias))
    )
      throw new AssistantCapabilityUsageError('请从实际目录选择明细，最多 16 组');
    return Object.fromEntries(
      Object.entries(input).map(([alias, fields]) => {
        const checked = names(fields);
        if (!checked.length) throw new AssistantCapabilityUsageError('明细至少展示一个字段');
        return [alias, checked];
      }),
    );
  };
  const presentation = (receipt: ConstructionDeliveryReceipt) => ({
    title: receipt.kind === 'PAGE' ? '页面配置已发布' : '访问入口已创建',
    lines: [
      current().saved?.content.objects.find((object) => object.key === receipt.objectKey)?.name ?? '业务页面',
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
            ? 'Prepare a separately confirmed management page (list, form, shared detail and quick search) for an initialized object. Read actual fields first; use existing field names, include all required business fields in formFields. Optional childFields maps discovered direct-child aliases to their actual field names; include all required child business fields and all confirmed requirement fields. Does not create relations. Replaces this construction plan’s page layout; never changes field rules. Human confirmation publishes.'
            : 'Prepare a separately confirmed menu entry for the current published standard management page in the system workbench, regardless of which governance entry published it. Does not grant business permissions. Query progress first; do not recreate an existing entry.',
        inputSchema: {
          ...objectSchema({
            objectKey: constructionObjectKeySchema(current),
            title: textSchema,
            ...(kind === 'PAGE'
              ? { listFields: fieldsSchema, formFields: fieldsSchema, searchFields: fieldsSchema }
              : {}),
          }),
          properties: {
            objectKey: constructionObjectKeySchema(current),
            title: textSchema,
            ...(kind === 'PAGE'
              ? {
                  listFields: fieldsSchema,
                  formFields: fieldsSchema,
                  searchFields: fieldsSchema,
                  childFields: { type: 'object', maxProperties: 16, additionalProperties: fieldsSchema },
                }
              : {}),
          },
        },
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
          ...(kind === 'PAGE' && value.childFields !== undefined
            ? { childFields: children(value.childFields) }
            : {}),
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
              lines: [stable.proposal.title, ...stable.lines],
              details: { title: '查看配置标识', lines: [stable.moduleAlias] },
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
      inputSchema: objectSchema({ objectKey: constructionObjectKeySchema(current) }),
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
        lines: ['本次建设验收已记录；后续改进读取当前治理配置，历史验收不证明修改后的配置正确。'],
      };
      async function accepted() {
        if (isCurrent()) {
          try {
            await onAccepted();
          } catch {
            return {
              ...result,
              lines: [...result.lines, '验收已记录，建设状态刷新失败；请重新读取，勿重复验收。'],
            };
          }
        }
        return result;
      }
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
              return accepted();
            } catch (error) {
              if (error instanceof AppError && [400, 401, 403, 404, 409, 422].includes(error.status ?? 0))
                throw new AssistantOperationRejectedError(error.message);
              throw error;
            }
          },
          async lookup() {
            return (await client.acceptance(planId, command.requestId)) ? accepted() : undefined;
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
          'Read actual publication, visible entry, runtime and pending acceptance for an initialized object. Restores progress after a page reload. This is configuration evidence only: businessDataStatus is NOT_QUERIED regardless of acceptance; use authorized business queries to establish whether records exist. Use returned menuId with workbench navigation to test the actual page.',
        inputSchema: objectSchema({ objectKey: constructionObjectKeySchema(current) }),
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
          title:
            current().saved?.content.objects.find((item) => item.key === progress.objectKey)?.name ??
            '业务配置进度',
          lines: [
            progress.pagePublished &&
            progress.entryVisible &&
            progress.runtimeStatus === 'ACTIVE' &&
            !progress.needsReview
              ? '页面和入口已可用。'
              : '配置尚需完善，请按当前进度继续。',
            progress.acceptanceConfirmed ? '本版业务效果已确认。' : '业务效果尚待验收，不代表没有录入数据。',
          ],
          details: {
            title: '查看配置证据与待办',
            lines: [
              progress.moduleAlias,
              `运行态：${progress.runtimeStatus}`,
              `页面：${progress.pagePublished ? '已发布' : '未完成'}；入口：${progress.entryVisible ? '当前用户可见' : '不可见'}`,
              '此进度未查询业务记录；是否已有数据须在当前业务范围内查询。',
              ...progress.remainingWork,
            ],
          },
        };
      },
    },
  ];
}
