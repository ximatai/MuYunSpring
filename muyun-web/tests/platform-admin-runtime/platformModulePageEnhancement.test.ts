import { describe, expect, it, vi } from 'vitest';
import {
  createWorkspaceViewDescriptor,
  createWorkspaceViewRegistry,
} from '@/platform-workbench/workspaceViews.ts';
import {
  dictionaryCategoryPageEnhancement,
  platformModuleActionPageEnhancement,
  platformModulePageEnhancement,
} from '@/platform-admin-runtime/platformModulePageEnhancement.ts';

import { workspaceViewContributions } from '@/platform-admin-runtime/workspaceViewContributions';

describe('platform module page enhancement', () => {
  it('limits dictionary category parents to folders', () => {
    const policy = dictionaryCategoryPageEnhancement.navigator?.treeParentPolicy;

    expect(policy?.canUseAsParent({ categoryKind: 'folder' })).toBe(true);
    expect(policy?.canUseAsParent({ categoryKind: 'dictionary' })).toBe(false);
    expect(policy?.rejectionMessage).toBe('字典类目只能挂在目录下');
  });

  it('declares action management as a hidden, locked child page of one module', () => {
    expect(platformModuleActionPageEnhancement).toMatchObject({
      target: { moduleAlias: 'platform.module_action' },
      navigator: {
        hidden: true,
        lockedEntry: {
          navigatorKey: 'module',
          unavailableDescription: '模块上下文不可用',
        },
      },
      standardActions: { disabled: ['create'] },
    });
  });

  it('opens actions in the shared action-management workspace scoped to its governed module', () => {
    const actions = platformModulePageEnhancement.detail?.actions ?? [];
    const openPage = vi.fn();
    const openWorkspaceTab = vi.fn();
    const record = { id: 'crm.customer', alias: 'crm.customer', title: '客户', moduleKind: 'dynamic' };

    actions
      .find((action) => action.key === 'module-actions-workspace')
      ?.run({
        record,
        openPage,
        openWorkspaceTab,
      } as never);
    actions
      .find((action) => action.key === 'module-ui-orchestration-workspace')
      ?.run({ record, openWorkspaceTab } as never);

    expect(openPage).not.toHaveBeenCalled();
    expect(openWorkspaceTab).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'platform.module.actions' }),
      { moduleAlias: 'crm.customer', moduleTitle: '客户', moduleKind: 'dynamic' },
    );
    expect(openWorkspaceTab).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'platform.module.governance' }),
      { moduleAlias: 'crm.customer', moduleTitle: '客户', governanceTab: 'overview' },
    );

    const [view, input] = openWorkspaceTab.mock.calls.find(
      ([workspace]) => workspace.type === 'platform.module.governance',
    )!;
    const descriptor = createWorkspaceViewDescriptor(
      {
        ...view,
        route: view.route ?? `/_workspace/${encodeURIComponent(view.type)}`,
        presentations: ['tab'],
      },
      input,
    );
    const restored = createWorkspaceViewRegistry([
      {
        ...view,
        route: view.route ?? `/_workspace/${encodeURIComponent(view.type)}`,
        presentations: ['tab'],
      },
    ]).resolve(descriptor);
    expect(restored).toMatchObject({
      view: { type: 'platform.module.governance' },
      input: { moduleAlias: 'crm.customer', moduleTitle: '客户', governanceTab: 'overview' },
    });
    expect(
      actions
        .find((action) => action.key === 'module-ui-orchestration-workspace')
        ?.state?.({
          id: 'iam.role',
          moduleKind: 'static',
        }),
    ).toEqual({ visible: false });
    expect(actions.map((action) => action.title)).toEqual([
      '业务编码',
      '审批流程',
      '添加到菜单',
      '动作',
      '低代码',
    ]);
  });

  it('exposes actions and the governance-backed low-code entry point in the module detail header', () => {
    const actions = platformModulePageEnhancement.detail?.actions ?? [];
    expect(actions.map((action) => action.key)).toEqual([
      'module-business-code',
      'module-workflow-workspace',
      'module-add-to-menu',
      'module-actions-workspace',
      'module-ui-orchestration-workspace',
    ]);
  });
  it('opens the code workbench for static API-only modules and registers it under its own capability module', () => {
    const action = (platformModulePageEnhancement.detail?.actions ?? []).find(
      (item) => item.key === 'module-business-code',
    )!;
    expect(action.state?.({ alias: 'api.invoice', moduleKind: 'static' })).toEqual({ visible: true });
    const openWorkspaceTab = vi.fn();
    action.run({ record: { alias: 'api.invoice', title: '接口单据' }, openWorkspaceTab } as never);
    const [view, input] = openWorkspaceTab.mock.calls[0]!;
    expect(workspaceViewContributions).toContain(view);
    expect(view.moduleAlias).toBe('platform.code_rule');
    expect(
      createWorkspaceViewRegistry([view]).resolve(createWorkspaceViewDescriptor(view, input)),
    ).toMatchObject({ input: { moduleAlias: 'api.invoice' } });
  });
});
