import { describe, expect, it } from 'vitest';
import { compactMenuTopOf } from '@/platform-workbench/workbenchLayout';

describe('compactMenuTopOf', () => {
  it('positions the compact menu below the actual topbar bottom', () => {
    expect(compactMenuTopOf(54, 0)).toBe(54);
    expect(compactMenuTopOf(196, 24)).toBe(172);
  });
});
