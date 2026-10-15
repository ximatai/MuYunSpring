import { createHash } from 'node:crypto';
import { expect, it } from 'vitest';
import type {
  ConstructionTask,
  ConstructionAcceptancePreview,
  ConstructionProgress,
  TextPresentation,
} from '@muyun/web-contracts';
import { createLanguageContext } from '@muyun/web-core';
import {
  constructionTaskPresentation,
  constructionProgressPresentation,
  constructionAcceptancePresentation,
} from '@/platform-workbench/constructionPresentation';

function resource(key: string, original: string, english: string): TextPresentation {
  return {
    source: {
      sourceKind: 'CODE',
      sourceId: `platform.construction.${key}`,
      sourceVersion: `sha256-${createHash('sha256').update(original, 'utf8').digest('hex')}`,
      originalText: original,
    },
    languages: {
      'zh-CN': {
        text: original,
        requestedTag: 'zh-CN',
        resourceTag: 'zh-CN',
        missing: false,
        fallback: false,
      },
      en: { text: english, requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false },
    },
  };
}
it('projects only declared construction explanation slots and keeps original business facts and statuses', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const packet: ConstructionTask = JSON.parse(
    JSON.stringify({
      planRevision: 2,
      unmappedRequirements: [
        {
          section: 'SCOPE',
          index: 0,
          statement: '用户要求',
          objectKey: '',
          fieldName: '',
          status: 'UNMAPPED',
          explanation: '尚未确认如何兑现；不能视为已实现',
        },
      ],
      objects: [
        {
          objectKey: 'order',
          title: '用户原始对象名称',
          complete: false,
          options: [{ action: 'VERIFY_BUSINESS', explanation: '平台固定下一步' }],
          requirements: [
            {
              section: 'RULE',
              index: 0,
              statement: '用户规则',
              objectKey: 'order',
              fieldName: 'total',
              status: 'CONFIGURATION_MATCHED',
              explanation: '用户写入的兑现说明',
            },
          ],
          progress: {
            moduleAlias: 'sample.order',
            runtimeStatus: 'ACTIVE',
            pagePublished: true,
            entryVisible: true,
            menuId: 'menu',
            needsReview: false,
            acceptanceConfirmed: false,
            businessDataStatus: 'NOT_QUERIED',
            remainingWork: ['平台固定待办'],
          },
        },
      ],
      textBindings: {
        '/objects/0/options/0/explanation': resource(
          'task.verify-business',
          '平台固定下一步',
          'Verify business behavior',
        ),
        '/objects/0/progress/remainingWork/0': resource(
          'progress.business-verification',
          '平台固定待办',
          'Business verification is pending',
        ),
        '/unmappedRequirements/0/explanation': resource(
          'requirement.unmapped',
          '尚未确认如何兑现；不能视为已实现',
          'The fulfillment method has not been confirmed',
        ),
        '/objects/0/title': resource(
          'task.invalid-slot',
          '用户原始对象名称',
          'Do not replace the business name',
        ),
        '/objects/0/complete': resource('task.invalid-slot', 'false', 'true'),
      },
    }),
  );
  const original = structuredClone(packet);
  const view = constructionTaskPresentation(packet, language);
  expect(view.objects[0]!.options[0]!.explanation).toBe('Verify business behavior');
  expect(view.objects[0]!.progress!.remainingWork).toEqual(['Business verification is pending']);
  expect(view.unmappedRequirements[0]!.explanation).toBe('The fulfillment method has not been confirmed');
  expect(view.objects[0]!.title).toBe('用户原始对象名称');
  expect(view.objects[0]!.requirements[0]!.explanation).toBe('用户写入的兑现说明');
  expect(view.objects[0]!.requirements[0]!.statement).toBe('用户规则');
  expect(view.objects[0]!.complete).toBe(false);
  expect(view.objects[0]!.progress!.businessDataStatus).toBe('NOT_QUERIED');
  language.setLanguage('zh-CN');
  expect(constructionTaskPresentation(packet, language).objects[0]!.options[0]!.explanation).toBe(
    '平台固定下一步',
  );
  expect(packet).toEqual(original);
});
it('localizes delivered acceptance resources without changing the approved fingerprint or user statements', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const text = resource('acceptance.rule', '业务规则：{statement}', 'Business rule: 用户规则 $1\\path');
  text.languages['zh-CN']!.text = '业务规则：用户规则 $1\\path';
  const preview: ConstructionAcceptancePreview = {
    objectKey: 'order',
    planRevision: 7,
    fingerprint: 'original-fingerprint',
    checks: ['业务规则：用户规则 $1\\path'],
    textBindings: {
      '/checks/0': text,
      '/fingerprint': resource('invalid', 'original-fingerprint', 'changed'),
    },
  };
  expect(constructionAcceptancePresentation(preview, language)).toMatchObject({
    checks: ['Business rule: 用户规则 $1\\path'],
    fingerprint: 'original-fingerprint',
    planRevision: 7,
  });
  expect(preview.checks).toEqual(['业务规则：用户规则 $1\\path']);
});
it('keeps raw display facts when resources are absent, belong to another source kind, or have no usable language', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const foreign = resource('progress.runtime-inactive', '原文', 'Changed');
  foreign.source.sourceKind = 'CONFIG';
  const missing = resource('progress.runtime-inactive', '{value}', 'Changed');
  missing.languages = {};
  const progress: ConstructionProgress = {
    objectKey: 'order',
    moduleAlias: 'sample.order',
    runtimeStatus: 'ACTIVE',
    pagePublished: true,
    entryVisible: false,
    menuId: null,
    needsReview: false,
    acceptanceConfirmed: false,
    businessDataStatus: 'NOT_QUERIED',
    remainingWork: ['配置原文', '包含参数的完整原文', '历史无来源文本'],
    receipts: [],
    textBindings: { '/remainingWork/0': foreign, '/remainingWork/1': missing },
  };
  expect(constructionProgressPresentation(progress, language).remainingWork).toEqual(progress.remainingWork);
});
