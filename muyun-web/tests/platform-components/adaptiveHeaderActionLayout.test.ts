import { describe, expect, it } from 'vitest';
import { resolveHeaderActionLayout } from '@/platform-components/adaptiveHeaderActionLayout';

describe('resolveHeaderActionLayout', () => {
  const actions = [
    { key: 'create', level: 'primary' as const, width: 80 },
    { key: 'edit', level: 'standard' as const, width: 70 },
    { key: 'delete', level: 'secondary' as const, width: 70 },
  ];

  it('keeps every action direct when the card header has enough room', () => {
    expect(resolveHeaderActionLayout(actions, 240, 60)).toEqual({
      directKeys: ['create', 'edit', 'delete'],
      overflowKeys: [],
    });
  });

  it('collapses lower business importance before standard and primary actions', () => {
    expect(resolveHeaderActionLayout(actions, 170, 60)).toEqual({
      directKeys: ['create'],
      overflowKeys: ['edit', 'delete'],
    });
  });

  it('withdraws equally standard actions incrementally instead of hiding the whole group', () => {
    const actionsWithTwoStandardItems = [
      { key: 'create', level: 'primary' as const, width: 80 },
      { key: 'action', level: 'standard' as const, width: 70 },
      { key: 'edit', level: 'standard' as const, width: 70 },
      { key: 'delete', level: 'secondary' as const, width: 70 },
    ];

    expect(resolveHeaderActionLayout(actionsWithTwoStandardItems, 250, 60)).toEqual({
      directKeys: ['create', 'action'],
      overflowKeys: ['edit', 'delete'],
    });
  });

  it('uses only 更多 when even the primary action cannot fit beside it', () => {
    expect(resolveHeaderActionLayout(actions, 100, 60)).toEqual({
      directKeys: [],
      overflowKeys: ['create', 'edit', 'delete'],
    });
  });
});
