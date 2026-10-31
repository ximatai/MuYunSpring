import { describe, expect, it, vi } from 'vitest';
import type { ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import { resolveModulePageActionControls } from '@/dynamic-page-runtime/modulePageActionControls';
import {
  createModulePageEnhancementRegistry,
  type ModulePageActionControlContribution,
} from '@/dynamic-page-runtime/modulePageEnhancements';
import { resolveRecordActions } from '@/platform-components/recordActionBarModel';

const control: ModulePageActionControlContribution = {
  key: 'talk',
  title: 'Talk',
  actionCode: 'talk',
  component: {},
  placement: 'after-primary',
};
const module = {
  moduleAlias: 'demo.device',
  action: () => ({ available: false, reason: 'Denied' }),
} as unknown as ModuleContext<QueryListRecord>;

describe('detail operation control contributions', () => {
  it('composes frontend controls and rejects cross-owner collisions and missing authorization declarations', () => {
    const enhancement = {
      id: 'device',
      target: { moduleAlias: 'demo.device' },
      detail: { controls: [control] },
    };
    const registry = createModulePageEnhancementRegistry([enhancement]);
    expect(registry.resolve('demo.device')?.detail?.controls).toEqual([control]);
    expect(registry.resolve('demo.device')?.list?.rowActions).toBeUndefined();
    expect(() => createModulePageEnhancementRegistry([enhancement, { ...enhancement, id: 'other' }])).toThrow(
      /key/,
    );
    expect(() =>
      createModulePageEnhancementRegistry([
        { ...enhancement, detail: { controls: [{ ...control, actionCode: '' }] } },
      ]),
    ).toThrow(/actionCode/);
    expect(() =>
      createModulePageEnhancementRegistry([
        { ...enhancement, detail: { controls: [{ ...control, key: '__platform-save' }] } },
      ]),
    ).toThrow(/key/);
  });

  it('shares record authorization and business state without exposing a mutable record or requiring a click handler', () => {
    const record = { id: 'a', nested: { value: 'original' } };
    const state = vi.fn(() => ({ disabled: false }));
    const options = {
      controls: [{ ...control, state }],
      record,
      stateContext: { module },
      sessionKey: 3,
      active: true,
      busy: true,
      refresh: vi.fn(),
    };
    const actions = resolveModulePageActionControls(options);
    expect(actions[0].disabled).toBe(true);
    expect(Object.isFrozen(actions[0].control?.record.nested)).toBe(true);
    expect(actions[0].control?.record).not.toBe(record);
    expect(actions[0]).not.toHaveProperty('run');
    const resolved = resolveRecordActions(module, actions, false, 'a');
    expect(resolved[0]).toMatchObject({ authorized: false, disabled: true, reason: 'Denied' });
    expect(resolveModulePageActionControls({ ...options, active: false })).toEqual([]);
    expect(resolveModulePageActionControls({ ...options, record: undefined })).toEqual([]);
  });
});
