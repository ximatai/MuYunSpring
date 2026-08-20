import { describe, expect, it } from 'vitest';
import { createModulePageEnhancementRegistry } from '@/dynamic-page-runtime/modulePageEnhancements.ts';
import { tenantModulePageEnhancement } from '@/platform-admin-runtime/tenantModulePageEnhancement.ts';

describe('tenant module page enhancement', () => {
  it('adds branding and application UI around the source-neutral standard host', () => {
    const enhancement = createModulePageEnhancementRegistry([tenantModulePageEnhancement]).resolve(
      'iam.tenant',
    );

    expect(enhancement?.form?.contributions.map((item) => item.location)).toContainEqual({
      surface: 'main',
      fieldName: 'workbenchBrandMode',
      placement: 'before',
    });
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
    expect(enhancement?.detail?.sections.map((item) => item.key)).toContain('tenant-applications');
    expect(enhancement?.detail?.actions.map((item) => item.key)).toContain('tenant-configure-applications');
  });
});
