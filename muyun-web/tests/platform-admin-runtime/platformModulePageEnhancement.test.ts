import { describe, expect, it, vi } from 'vitest';
import { tabKeyOf } from '@muyun/platform-workbench';
import {
  createWorkspaceViewDescriptor,
  createWorkspaceViewRegistry,
} from '@/platform-workbench/workspaceViews.ts';
import {
  dictionaryCategoryPageEnhancement,
  platformModuleActionPageEnhancement,
  platformModulePageEnhancement,
} from '@/platform-admin-runtime/platformModulePageEnhancement.ts';

import { workflowConfigurationWorkspaceView } from '@/views/workflowConfigurationWorkspaceView';

import { moduleCodeCatalog } from '@/platform-admin-runtime/moduleCodeConfiguration';
import type { ModuleContext } from '@muyun/web-core';
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

  it('identifies a workflow tab by module and definition IDs rather than display titles', () => {
    const input = {
      moduleAlias: 'demo.purchase',
      definitionId: 'a',
      moduleTitle: '采购',
      definitionTitle: '审批',
    };
    const first = createWorkspaceViewDescriptor(workflowConfigurationWorkspaceView, input);
    const renamed = createWorkspaceViewDescriptor(workflowConfigurationWorkspaceView, {
      ...input,
      definitionTitle: '新版审批',
      moduleTitle: '采购单',
    });
    const other = createWorkspaceViewDescriptor(workflowConfigurationWorkspaceView, {
      ...input,
      definitionId: 'b',
    });
    expect(tabKeyOf(first)).toBe(tabKeyOf(renamed));
    expect(tabKeyOf(first)).not.toBe(tabKeyOf(other));
    expect(workflowConfigurationWorkspaceView.parse({ moduleAlias: 'demo.purchase' })).toBeUndefined();
  });

  it('opens workflow management in a standard drawer without navigating away', () => {
    const openDrawer = vi.fn(),
      openWorkspaceTab = vi.fn();
    platformModulePageEnhancement
      .detail!.actions!.find((action) => action.key === 'module-workflow-workspace')!
      .run({ record: { alias: 'demo.purchase' }, openDrawer, openWorkspaceTab } as never);
    expect(openDrawer).toHaveBeenCalledWith(
      expect.objectContaining({
        title: '审批流程',
        width: 'wide',
        dismissal: 'dismissible',
        component: expect.anything(),
      }),
    );
    expect(openWorkspaceTab).not.toHaveBeenCalled();
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
  it('only opens code configuration for modules with actual configurable fields, including API-only modules', async () => {
    const action = (platformModulePageEnhancement.detail?.actions ?? []).find(
      (item) => item.key === 'module-business-code',
    )!;
    const module = {
      http: {
        request: vi.fn(async ({ path }: { path: string }) => ({
          configurable: path.includes('api.invoice'),
          reason: 'not-declared',
        })),
      },
    } as unknown as ModuleContext<Record<string, unknown>>;
    const stateContext = { module };
    expect(action.state?.({ alias: 'api.invoice', moduleKind: 'static' }, stateContext)).toEqual({
      visible: false,
    });
    await moduleCodeCatalog(module).refresh('api.invoice');
    expect(action.state?.({ alias: 'api.invoice', moduleKind: 'static' }, stateContext)).toEqual({
      visible: true,
    });
    expect(action.state?.({ alias: 'platform.localization', moduleKind: 'static' }, stateContext)).toEqual({
      visible: false,
    });
    expect(action.state?.({ alias: 'draft.empty', moduleKind: 'dynamic' }, stateContext)).toEqual({
      visible: false,
    });
    const openWorkspaceTab = vi.fn();
    action.run({ record: { alias: 'api.invoice', title: '接口单据' }, module, openWorkspaceTab } as never);
    const [view, input] = openWorkspaceTab.mock.calls[0]!;
    expect(workspaceViewContributions).toContain(view);
    expect(view.moduleAlias).toBe('platform.code_rule');
    expect(
      createWorkspaceViewRegistry([view]).resolve(createWorkspaceViewDescriptor(view, input)),
    ).toMatchObject({ input: { moduleAlias: 'api.invoice' } });
  });
});
