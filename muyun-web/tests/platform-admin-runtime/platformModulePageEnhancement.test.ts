import { describe, expect, it, vi } from 'vitest';
import {
  createWorkspaceViewDescriptor,
  createWorkspaceViewRegistry,
} from '@/platform-workbench/workspaceViews.ts';
import { platformModulePageEnhancement } from '@/platform-admin-runtime/platformModulePageEnhancement.ts';

describe('platform module page enhancement', () => {
  it('opens the existing action and metadata workspace views with restorable record input', () => {
    const actions = platformModulePageEnhancement.detail?.actions ?? [];
    const openWorkspaceTab = vi.fn();
    const record = { id: 'crm.customer', alias: 'crm.customer', title: '客户', moduleKind: 'dynamic' };

    actions
      .find((action) => action.key === 'module-actions-workspace')
      ?.run({
        record,
        openWorkspaceTab,
      } as never);
    actions
      .find((action) => action.key === 'module-metadata-orchestration-workspace')
      ?.run({
        record,
        openWorkspaceTab,
      } as never);

    expect(openWorkspaceTab).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ type: 'platform.module.actions' }),
      { moduleAlias: 'crm.customer', moduleTitle: '客户', moduleKind: 'dynamic' },
    );
    expect(openWorkspaceTab).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ type: 'platform.module.metadata-orchestration' }),
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
      view: { type: 'platform.module.actions' },
      input: { moduleAlias: 'crm.customer', moduleTitle: '客户', moduleKind: 'dynamic' },
    });
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
