import { expect, it } from 'vitest';
import { recordCreationReadiness } from '@/dynamic-page-runtime/recordCreationReadiness';

const ready = {
  tenantReady: true,
  pageReady: true,
  permitted: true,
  editing: false,
  busy: false,
  scopeReady: true,
};

it.each([
  [{ tenantReady: false, permitted: false, pageReady: false }, 'TENANT_REQUIRED'],
  [{ pageReady: false, permitted: false }, 'LOADING'],
  [{ permitted: false }, 'FORBIDDEN'],
  [{ editing: true }, 'DRAFT_ACTIVE'],
  [{ busy: true }, 'BUSY'],
  [{ scopeReady: false }, 'SCOPE_REQUIRED'],
] as const)(
  'explains the first actionable prerequisite without assuming a permission failure: %j',
  (state, reason) => {
    expect(recordCreationReadiness({ ...ready, ...state })).toMatchObject({ ready: false, reason });
  },
);

it('allows creation after all prerequisites are resolved without module-specific rules', () => {
  expect(recordCreationReadiness(ready)).toEqual({ ready: true });
});
