import { describe, expect, it } from 'vitest';
import {
  compactMenuTopOf,
  compactMenuPanelOutlinePath,
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
  it('joins a sidebar trigger and its adjoining flyout into one outline', () => {
    expect(
      floatingMenuPanelOutlinePath(
        { left: 240, top: 200, width: 280, height: 140 },
        { left: 20, top: 200, height: 29 },
      ),
    ).toBe(
      'M 240 200 H 512 Q 520 200 520 208 V 332 Q 520 340 512 340 H 240 V 229 H 25 Q 20 229 20 224 V 205 Q 20 200 25 200 H 240 V 200',
    );
  });
});

describe('compactMenuPanelOutlinePath', () => {
  it('joins the compact-menu trigger and dropdown into one continuous outline', () => {
    expect(
      compactMenuPanelOutlinePath(
        { left: 8, top: 54, right: 240, bottom: 360 },
        { left: 8, top: 8, right: 176 },
      ),
    ).toBe(
      'M 13 8 H 171 Q 176 8 176 13 V 54 H 236 Q 240 54 240 58 V 356 Q 240 360 236 360 H 12 Q 8 360 8 356 V 58 Q 8 54 12 54 H 8 V 13 Q 8 8 13 8',
    );
  });

  it('extends the compact outline through an adjoining Mega flyout', () => {
    expect(
      compactMenuPanelOutlinePath(
        { left: 8, top: 54, right: 240, bottom: 360 },
        { left: 8, top: 8, right: 176 },
        5,
        4,
        { left: 240, top: 100, right: 520, bottom: 300 },
      ),
    ).toBe(
      'M 13 8 H 171 Q 176 8 176 13 V 54 H 240 V 100 H 512 Q 520 100 520 108 V 292 Q 520 300 512 300 H 240 V 356 Q 240 360 236 360 H 12 Q 8 360 8 356 V 58 Q 8 54 12 54 H 8 V 13 Q 8 8 13 8',
    );
  });

  it('treats the one-pixel border seam as one shared Mega surface', () => {
    expect(
      compactMenuPanelOutlinePath(
        { left: 8, top: 54, right: 240, bottom: 360 },
        { left: 8, top: 8, right: 176 },
        5,
        4,
        { left: 239, top: 100, right: 520, bottom: 300 },
      ),
    ).toBe(
      'M 13 8 H 171 Q 176 8 176 13 V 54 H 240 V 100 H 512 Q 520 100 520 108 V 292 Q 520 300 512 300 H 240 V 356 Q 240 360 236 360 H 12 Q 8 360 8 356 V 58 Q 8 54 12 54 H 8 V 13 Q 8 8 13 8',
    );
  });

  it('keeps the shared outline when the Mega flyout is taller than the compact menu', () => {
    expect(
      compactMenuPanelOutlinePath(
        { left: 8, top: 54, right: 240, bottom: 240 },
        { left: 8, top: 8, right: 176 },
        5,
        4,
        { left: 240, top: 100, right: 520, bottom: 360 },
      ),
    ).toBe(
      'M 13 8 H 171 Q 176 8 176 13 V 54 H 240 V 100 H 512 Q 520 100 520 108 V 352 Q 520 360 512 360 H 240 V 240 H 12 Q 8 240 8 236 V 58 Q 8 54 12 54 H 8 V 13 Q 8 8 13 8',
    );
  });
});
