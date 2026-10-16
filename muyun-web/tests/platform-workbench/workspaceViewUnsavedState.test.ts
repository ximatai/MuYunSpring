import { createLanguageContext } from '@/web-core';
import { afterEach, expect, it } from 'vitest';
import {
  clearWorkspaceViewUnsavedState,
  registerWorkspaceViewUnsavedState,
  workspaceViewBusyStateSources,
  workspaceViewUnsavedStateSources,
} from '@/platform-workbench/workspaceViewUnsavedState';

const pageKey = 'business-route:/_workspace/customer:customer-1';

afterEach(() => clearWorkspaceViewUnsavedState(pageKey));

it('collects only the active local draft signals for a workspace tab', () => {
  let metadataDirty = false;
  const unregisterMetadata = registerWorkspaceViewUnsavedState(pageKey, '数据模型', () => metadataDirty);
  registerWorkspaceViewUnsavedState(pageKey, '页面配置', () => true);

  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual(['页面配置']);

  metadataDirty = true;
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual(['数据模型', '页面配置']);

  unregisterMetadata();
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual(['页面配置']);
});

it('treats a failed optional signal as non-blocking', () => {
  registerWorkspaceViewUnsavedState(pageKey, '失效面板', () => {
    throw new Error('stale panel');
  });

  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual([]);
});

it('reports in-flight mutations separately from unsaved drafts', () => {
  let busy = false;
  registerWorkspaceViewUnsavedState(
    pageKey,
    '记录详情',
    () => false,
    () => busy,
  );

  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual([]);
  expect(workspaceViewBusyStateSources(pageKey)).toEqual([]);

  busy = true;
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual([]);
  expect(workspaceViewBusyStateSources(pageKey)).toEqual(['记录详情']);
});

it('keeps identically named panels independent when one unmounts', () => {
  const unregisterFirst = registerWorkspaceViewUnsavedState(pageKey, '审批办理', () => true);
  const unregisterSecond = registerWorkspaceViewUnsavedState(
    pageKey,
    '审批办理',
    () => false,
    () => true,
  );
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual(['审批办理']);
  expect(workspaceViewBusyStateSources(pageKey)).toEqual(['审批办理']);
  unregisterSecond();
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual(['审批办理']);
  expect(workspaceViewBusyStateSources(pageKey)).toEqual([]);
  unregisterFirst();
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual([]);
});

it('reads current localized source names without replacing or dropping draft registrations', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const source = () => language.t('platform.screen.useModulePageSession.record-details', '记录详情');
  const unregister = registerWorkspaceViewUnsavedState(
    pageKey,
    source(),
    () => true,
    () => true,
    source,
  );
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual(['Record details']);
  language.setLanguage('zh-CN');
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual(['记录详情']);
  expect(workspaceViewBusyStateSources(pageKey)).toEqual(['记录详情']);
  unregister();
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual([]);
  expect(workspaceViewBusyStateSources(pageKey)).toEqual([]);
});

it('retains draft and busy protection when a localized source title fails', () => {
  registerWorkspaceViewUnsavedState(
    pageKey,
    'original source',
    () => true,
    () => true,
    () => {
      throw new Error('display failed');
    },
  );
  expect(workspaceViewUnsavedStateSources(pageKey)).toEqual(['original source']);
  expect(workspaceViewBusyStateSources(pageKey)).toEqual(['original source']);
});
