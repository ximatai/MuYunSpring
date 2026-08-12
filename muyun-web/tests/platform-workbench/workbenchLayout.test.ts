import { describe, expect, it } from 'vitest';
import {
  compactMenuTopOf,
  effectiveWorkbenchMenuPresentation,
  floatingMenuPanelOutlinePath,
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

describe('floatingMenuPanelOutlinePath', () => {
  it('draws a standalone panel outline when no menu row is connected', () => {
    expect(floatingMenuPanelOutlinePath({ left: 180, top: 8, width: 320, height: 200 })).toBe(
      'M 180 8 H 492 Q 500 8 500 16 V 200 Q 500 208 492 208 H 180',
    );
  });

  it('connects the panel outline to its active menu row', () => {
    expect(
      floatingMenuPanelOutlinePath(
        { left: 180, top: 8, width: 320, height: 200 },
        { left: 8, top: 36, height: 34 },
        6,
      ),
    ).toBe(
      'M 180 8 H 492 Q 500 8 500 16 V 200 Q 500 208 492 208 H 180 V 70 H 14 Q 8 70 8 64 V 42 Q 8 36 14 36 H 180 V 8',
    );
  });
});
