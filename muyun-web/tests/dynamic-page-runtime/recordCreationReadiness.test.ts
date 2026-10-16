import { createLanguageContext } from '@/web-core';
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

it('explains that an existing draft remains editable while another creation is blocked', () => {
  expect(recordCreationReadiness({ ...ready, editing: true })).toMatchObject({
    ready: false,
    reason: 'DRAFT_ACTIVE',
    message: expect.stringContaining('可继续编辑'),
  });
});

it('keeps the same blocking facts when scoped language changes', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const state = { ...ready, editing: true };
  const before = recordCreationReadiness(state, language);
  expect(before.message).toContain('An unsaved draft is open');
  language.setLanguage('zh-CN');
  const after = recordCreationReadiness(state, language);
  expect(after.reason).toBe(before.reason);
  expect(after.ready).toBe(false);
  expect(after.message).toContain('可继续编辑');
  expect(state).toEqual({ ...ready, editing: true });
});
