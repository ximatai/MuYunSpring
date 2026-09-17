import { describe, expect, expectTypeOf, it } from 'vitest';
import type { UiDrawerWidth } from '@/vue-ui-antdv/drawerWidth';
import { isUiDrawerWidth, resolveUiDrawerWidth, uiDrawerWidths } from '@/vue-ui-antdv/drawerWidth';

describe('drawer widths', () => {
  it('resolves the stable platform tiers to their rendered widths', () => {
    expect(uiDrawerWidths).toEqual({
      compact: 420,
      narrow: 480,
      standard: 520,
      wide: 760,
      extraWide: 960,
    });
    expect(resolveUiDrawerWidth('standard')).toBe(520);
    expect(resolveUiDrawerWidth('wide')).toBe(760);
  });

  it('limits consumers to the platform vocabulary', () => {
    expectTypeOf<UiDrawerWidth>().toEqualTypeOf<
      'compact' | 'narrow' | 'standard' | 'wide' | 'extraWide'
    >();
    expect(isUiDrawerWidth('compact')).toBe(true);
    expect(isUiDrawerWidth('980')).toBe(false);
  });
});
