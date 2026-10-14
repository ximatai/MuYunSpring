import {
  constructionObjectKeySchema,
  requireConfirmedConstructionPlan,
  type ConstructionPlanState,
} from './constructionPlanGuard';
import {
  platformLanguageContext,
  type LanguageContext,
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
  language: LanguageContext = platformLanguageContext,
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
      throw new AssistantCapabilityUsageError(
        language.t('platform.screen.constructionDelivery.invalid-construction-parameters', '建设参数无效'),
      );
    return input as Record<string, unknown>;
  };
  const text = (value: unknown) => {
    if (typeof value !== 'string' || !value.trim() || value.length > 120)
      throw new AssistantCapabilityUsageError(
        language.t(
          'platform.screen.constructionDelivery.construction-parameters-are-empty-or-too-long',
          '建设参数为空或过长',
        ),
      );
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
      const before = requireConfirmedConstructionPlan(current, language);
      const planId = before.saved.planId;
      const preview = await client.previewAcceptance(planId, (input as { objectKey: string }).objectKey);
      const command = {
        requestId: createUuid(),
        objectKey: preview.objectKey,
        fingerprint: preview.fingerprint,
      };
      const { isCurrent } = before;
      const result = {
        title: language.t(
          'platform.screen.constructionDelivery.human-acceptance-recorded',
          '已记录人工验收通过',
        ),
        lines: [
          language.t(
            'platform.screen.constructionDelivery.construction-acceptance-has-been-recorded-read-current-governance-configuration-for',
            '本次建设验收已记录；后续改进读取当前治理配置，历史验收不证明修改后的配置正确。',
          ),
        ],
      };
      async function accepted() {
        if (isCurrent()) {
          try {
            await onAccepted();
          } catch {
            return {
              ...result,
              lines: [
                ...result.lines,
                language.t(
                  'platform.screen.constructionDelivery.acceptance-recorded-but-construction-status-could-not-be-refreshed-read',
                  '验收已记录，建设状态刷新失败；请重新读取，勿重复验收。',
                ),
              ],
            };
          }
        }
        return result;
      }
      context.commitInternalState(() => {
        acceptance = {
          modelSummary: language.t(
            'platform.screen.constructionDelivery.record-the-user-s-human-acceptance-of-the-current-requirements',
            '记录用户对当前需求及配置基线的人工验收。必须先实际核对，不自动证明业务要求已经实现。',
          ),
          confirmLabel: language.t(
            'platform.screen.constructionDelivery.i-have-verified-and-accept-the-result',
            '我已核对并确认验收通过',
          ),
          expiresAt: Date.now() + 5 * 60_000,
          isCurrent,
          presentation: {
            title: language.t(
              'platform.screen.constructionDelivery.business-acceptance-confirmation',
              '业务验收确认',
            ),
            lines: [
              language.t(
                'platform.screen.constructionDelivery.requirements-revision-value',
                '需求第 {value1} 版',
                { value1: preview.planRevision },
              ),
              language.t(
                'platform.screen.constructionDelivery.enter-records-query-and-inspect-business-results-first-then-verify',
                '请先实际录入、查询并检查业务结果，逐项核对下方完整清单。确认表示你已核对通过，不是让系统自动证明所有需求。',
              ),
            ],
            details: {
              title: language.t(
                'platform.screen.constructionDelivery.view-the-full-acceptance-checklist',
                '查看完整验收清单',
              ),
              lines: preview.checks,
            },
          },
          async execute() {
            if (!isCurrent())
              throw new AssistantOperationRejectedError(
                language.t(
                  'platform.screen.constructionDelivery.the-acceptance-context-changed',
                  '验收上下文已变化',
                ),
              );
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
      if (!acceptance)
        throw new AssistantCapabilityUsageError(
          language.t(
            'platform.screen.constructionDelivery.review-the-acceptance-scope-first',
            '请先核对验收范围',
          ),
        );
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
        if (!plan)
          throw new AssistantCapabilityUsageError(
            language.t(
              'platform.screen.constructionPlanSession.restore-a-confirmed-plan-first',
              '请先恢复已确认方案',
            ),
          );
        return client.progress(plan.planId, (input as { objectKey: string }).objectKey);
      },
      present: (value) => {
        const progress = value as Awaited<ReturnType<ConstructionPlanClient['progress']>>;
        return {
          title:
            current().saved?.content.objects.find((item) => item.key === progress.objectKey)?.name ??
            language.t(
              'platform.screen.constructionDelivery.business-configuration-progress',
              '业务配置进度',
            ),
          lines: [
            progress.pagePublished &&
            progress.entryVisible &&
            progress.runtimeStatus === 'ACTIVE' &&
            !progress.needsReview
              ? language.t(
                  'platform.screen.constructionDelivery.page-and-entry-are-available',
                  '页面和入口已可用。',
                )
              : language.t(
                  'platform.screen.constructionDelivery.configuration-needs-more-work-continue-from-current-progress',
                  '配置尚需完善，请按当前进度继续。',
                ),
            progress.acceptanceConfirmed
              ? language.t(
                  'platform.screen.constructionDelivery.business-behavior-for-this-revision-has-been-accepted',
                  '本版业务效果已确认。',
                )
              : language.t(
                  'platform.screen.constructionDelivery.business-acceptance-is-pending-this-does-not-indicate-whether-records',
                  '业务效果尚待验收，不代表没有录入数据。',
                ),
          ],
          details: {
            title: language.t(
              'platform.screen.constructionDelivery.view-configuration-evidence-and-remaining-work',
              '查看配置证据与待办',
            ),
            lines: [
              progress.moduleAlias,
              language.t('platform.screen.constructionDelivery.runtime-value', '运行态：{value1}', {
                value1: progress.runtimeStatus,
              }),
              language.t(
                'platform.screen.constructionDelivery.page-value-entry-value',
                '页面：{value1}；入口：{value2}',
                {
                  value1: progress.pagePublished
                    ? language.t('platform.workflow.published', '已发布')
                    : language.t('platform.screen.RecordQueryListPanel.incomplete', '未完成'),
                  value2: progress.entryVisible
                    ? language.t(
                        'platform.screen.constructionDelivery.visible-to-the-current-user',
                        '当前用户可见',
                      )
                    : language.t('platform.screen.ConstructionPlanCard.not-visible', '不可见'),
                },
              ),
              language.t(
                'platform.screen.constructionDelivery.this-progress-query-does-not-read-business-records-query-the',
                '此进度未查询业务记录；是否已有数据须在当前业务范围内查询。',
              ),
              ...progress.remainingWork,
            ],
          },
        };
      },
    },
  ];
}
