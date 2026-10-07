import {
  constructionObjectKeySchema,
  requireConfirmedConstructionPlan,
  type ConstructionPlanState,
} from './constructionPlanGuard';
import {
  createUuid,
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
  onAccepted: () => Promise<void> = async () => {},
): AssistantCapability[] {
  const objectSchema = (properties: Record<string, unknown>) => ({
    type: 'object',
    additionalProperties: false,
    required: Object.keys(properties),
    properties,
  });
  const parseObject = (input: unknown, allowed = ['objectKey']): Record<string, unknown> => {
    if (
      !input ||
      typeof input !== 'object' ||
      Array.isArray(input) ||
      Object.keys(input).some((key) => !allowed.includes(key))
    )
      throw new AssistantCapabilityUsageError('建设参数无效');
    return input as Record<string, unknown>;
  };
  const text = (value: unknown) => {
    if (typeof value !== 'string' || !value.trim() || value.length > 120)
      throw new AssistantCapabilityUsageError('建设参数为空或过长');
    return value.trim();
  };
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
        requestId: createUuid(),
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
