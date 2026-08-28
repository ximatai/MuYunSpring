import { describe, expect, it, vi } from 'vitest';
import {
  createWorkspaceViewDescriptor,
  createWorkspaceViewRegistry,
} from '@/platform-workbench/workspaceViews.ts';
import {
  platformModuleActionPageEnhancement,
  platformModulePageEnhancement,
} from '@/platform-admin-runtime/platformModulePageEnhancement.ts';

describe('platform module page enhancement', () => {
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

  it('opens actions as a descriptor-driven page scoped to its governed module', () => {
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
      .find((action) => action.key === 'module-metadata-orchestration-workspace')
      ?.run({
        record,
        openWorkspaceTab,
      } as never);
    actions
      .find((action) => action.key === 'module-manual-action-binding-workspace')
      ?.run({ record, openWorkspaceTab } as never);
    actions
      .find((action) => action.key === 'module-ui-orchestration-workspace')
      ?.run({ record, openWorkspaceTab } as never);

    expect(openPage).toHaveBeenCalledWith(
      expect.objectContaining({
        target: { moduleAlias: 'platform.module_action', pageMode: 'LIST' },
        params: {
          _muyunNavigatorModuleAlias: 'platform.module',
          _muyunNavigatorRecordId: 'crm.customer',
        },
      }),
    );
    expect(openWorkspaceTab).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ type: 'platform.module.metadata-orchestration' }),
      { moduleAlias: 'crm.customer', moduleTitle: '客户' },
    );

    expect(openWorkspaceTab).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ type: 'platform.module.actions' }),
      { moduleAlias: 'crm.customer', moduleTitle: '客户', moduleKind: 'dynamic' },
    );
    expect(openWorkspaceTab).toHaveBeenNthCalledWith(
      3,
      expect.objectContaining({ type: 'platform.module.ui-orchestration' }),
      { moduleAlias: 'crm.customer', moduleTitle: '客户' },
    );

    const [view, input] = openWorkspaceTab.mock.calls[0];
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
      view: { type: 'platform.module.metadata-orchestration' },
      input: { moduleAlias: 'crm.customer', moduleTitle: '客户' },
    });
    expect(
      actions
        .find((action) => action.key === 'module-manual-action-binding-workspace')
        ?.state?.({
          id: 'iam.role',
          moduleKind: 'static',
        }),
    ).toEqual({ visible: false });
    expect(
      actions
        .find((action) => action.key === 'module-ui-orchestration-workspace')
        ?.state?.({ id: 'iam.role', moduleKind: 'static' }),
    ).toEqual({ visible: false });
  });

  it('only exposes metadata for dynamic modules and OpenAPI after the authorized catalog has listed it', async () => {
    const actions = platformModulePageEnhancement.detail?.actions ?? [];
    const staticRecord = { id: 'iam.role', alias: 'iam.role', moduleKind: 'static' };
    const documentedRecord = {
      id: 'crm.customer',
      alias: 'crm.customer',
      title: '客户',
      moduleKind: 'dynamic',
    };
    const undocumentedRecord = { id: 'crm.private', alias: 'crm.private', moduleKind: 'dynamic' };
    const metadata = actions.find((action) => action.key === 'module-metadata-orchestration-workspace');
    const openApi = actions.find((action) => action.key === 'module-openapi-page');

    expect(metadata?.state?.(staticRecord)).toEqual({ visible: false });
    expect(openApi?.state?.(documentedRecord)).toEqual({ visible: false });

    platformModulePageEnhancement.activate?.({
      module: {
        http: { request: vi.fn().mockResolvedValue([{ moduleAlias: 'crm.customer' }]) },
      },
    } as never);
    await Promise.resolve();
    await Promise.resolve();

    expect(openApi?.state?.(documentedRecord)).toEqual({ visible: true });
    expect(openApi?.state?.(undocumentedRecord)).toEqual({ visible: false });
    const openPage = vi.fn();
    openApi?.run({ record: documentedRecord, openPage } as never);
    expect(openPage).toHaveBeenCalledWith(
      expect.objectContaining({ target: { route: '/openapi/crm.customer', moduleAlias: 'crm.customer' } }),
    );
  });
});
