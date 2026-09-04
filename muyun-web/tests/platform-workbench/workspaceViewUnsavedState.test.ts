import { afterEach, expect, it } from 'vitest';
import {
  clearWorkspaceViewUnsavedState,
  registerWorkspaceViewUnsavedState,
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
