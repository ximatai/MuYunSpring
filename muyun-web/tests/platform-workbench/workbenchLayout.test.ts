import { describe, expect, it } from 'vitest';
import {
  compactMenuTopOf,
  effectiveWorkbenchMenuPresentation,
  floatingPanelTopOf,
} from '@/platform-workbench/workbenchLayout';

describe('compactMenuTopOf', () => {
  it('positions the compact menu below the actual topbar bottom', () => {
    expect(compactMenuTopOf(54, 0)).toBe(54);
    expect(compactMenuTopOf(196, 24)).toBe(172);
  });
});

describe('effectiveWorkbenchMenuPresentation', () => {
  it('forces compact navigation on narrow viewports without losing the desktop preference', () => {
    expect(effectiveWorkbenchMenuPresentation('expanded', true)).toBe('compact');
    expect(effectiveWorkbenchMenuPresentation('expanded', false)).toBe('expanded');
    expect(effectiveWorkbenchMenuPresentation('compact', false)).toBe('compact');
  });
});

describe('floatingPanelTopOf', () => {
  it('keeps a short panel aligned with its anchor after the real height is known', () => {
    expect(floatingPanelTopOf(140, 153, 700, 140)).toBe(0);
  });

  it('moves a panel upward only when its real height would cross the viewport margin', () => {
    expect(floatingPanelTopOf(650, 200, 700, 100)).toBe(392);
  });
});
