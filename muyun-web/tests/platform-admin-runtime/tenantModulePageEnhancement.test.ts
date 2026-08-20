import { describe, expect, it } from 'vitest';
import { createModulePageEnhancementRegistry } from '@/dynamic-page-runtime/modulePageEnhancements.ts';
import { tenantModulePageEnhancement } from '@/platform-admin-runtime/tenantModulePageEnhancement.ts';

describe('tenant module page enhancement', () => {
  it('adds branding and application UI around the source-neutral standard host', () => {
    const enhancement = createModulePageEnhancementRegistry([tenantModulePageEnhancement]).resolve(
      'iam.tenant',
    );

    expect(enhancement?.form?.contributions).toEqual([]);
    const titlePolicy = enhancement?.form?.fieldPolicies?.find((item) => item.fieldName === 'workbenchTitle');
    expect(
      titlePolicy?.visible?.({
        mode: 'edit',
        draft: { workbenchBrandMode: 'logoOnly' },
        fields: [],
        formSessionKey: 1,
      }),
    ).toBe(false);
    expect(
      titlePolicy?.visible?.({
        mode: 'edit',
        draft: { workbenchBrandMode: 'logoWithTitle' },
        fields: [],
        formSessionKey: 1,
      }),
    ).toBe(true);
    expect(enhancement?.detail?.sections?.map((item) => item.key)).toContain('tenant-applications');
    expect(enhancement?.detail?.actions?.map((item) => item.key)).toContain('tenant-configure-applications');
  });

  it('opens the configured-application drawer through the standard detail action context', async () => {
    const action = tenantModulePageEnhancement.detail?.actions?.find(
      (candidate) => candidate.key === 'tenant-configure-applications',
    );
    const drawers: unknown[] = [];

    await action?.run({
      record: { id: 'tenant-a' },
      module: {} as never,
      refreshList: () => undefined,
      reload: () => undefined,
      openDrawer(drawer) {
        drawers.push(drawer);
      },
      openWorkspaceTab: () => undefined,
      openPage: () => undefined,
    });

    expect(drawers).toHaveLength(1);
    expect(drawers[0]).toMatchObject({ title: '配置应用', width: 760 });
  });
});
