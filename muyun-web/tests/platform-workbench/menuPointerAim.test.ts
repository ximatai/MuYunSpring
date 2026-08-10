import { describe, expect, it } from 'vitest';
import { isPointerHeadingToMenuPanel } from '@/platform-workbench/menuPointerAim';

describe('isPointerHeadingToMenuPanel', () => {
  const origin = { x: 200, y: 100 };
  const panel = { left: 220, top: 50, bottom: 250 };

  it('keeps a diagonal pointer path towards the open panel', () => {
    expect(isPointerHeadingToMenuPanel({ x: 210, y: 145 }, origin, panel)).toBe(true);
  });

  it('rejects paths that move away from the panel corridor', () => {
    expect(isPointerHeadingToMenuPanel({ x: 195, y: 100 }, origin, panel)).toBe(false);
    expect(isPointerHeadingToMenuPanel({ x: 210, y: 20 }, origin, panel)).toBe(false);
  });
});
