import { expect, it } from 'vitest';
import { layoutMenuGroups } from '@/platform-workbench/menuGroupLayout';

it('balances the seven uneven platform groups without changing their configured sequence', () => {
  const heights = [160, 256, 128, 160, 64, 160, 224];
  const ranges = layoutMenuGroups(heights, 4, 592, 20);
  expect(ranges).toEqual([
    { start: 0, end: 2 },
    { start: 2, end: 5 },
    { start: 5, end: 7 },
  ]);
  expect(
    ranges.map(
      ({ start, end }) => heights.slice(start, end).reduce((a, b) => a + b, 0) + (end - start - 1) * 20,
    ),
  ).toEqual([436, 392, 404]);
});

it('puts an equal-cost remainder on the right rather than leaving the first column short', () => {
  expect(layoutMenuGroups([100, 100, 100], 2, 220, 20)).toEqual([
    { start: 0, end: 2 },
    { start: 2, end: 3 },
  ]);
});

it('uses fewer columns for small menus and stops adding columns when an oversized group cannot shrink', () => {
  expect(layoutMenuGroups([30, 30], 4, 100, 20)).toEqual([{ start: 0, end: 2 }]);
  expect(layoutMenuGroups([1000, 20, 20], 4, 300, 20)).toEqual([
    { start: 0, end: 1 },
    { start: 1, end: 3 },
  ]);
  expect(layoutMenuGroups([], 4, 300, 20)).toEqual([]);
});

it.each([1, 2, 3, 4])('finds the minimum possible tallest column within a %i-column width limit', (limit) => {
  const heights = [80, 240, 60, 180, 30, 120, 200];
  const ranges = layoutMenuGroups(heights, limit, 0, 20);
  const heightOf = (start: number, end: number) =>
    heights.slice(start, end).reduce((a, b) => a + b, 0) + (end - start - 1) * 20;
  // Independently enumerate every legal contiguous partition for this small fixture.
  let optimum = Infinity;
  function enumerate(start: number, remaining: number, peak: number) {
    if (start === heights.length) {
      optimum = Math.min(optimum, peak);
      return;
    }
    if (!remaining) return;
    for (let end = start + 1; end <= heights.length; end++)
      enumerate(end, remaining - 1, Math.max(peak, heightOf(start, end)));
  }
  enumerate(0, limit, 0);
  expect(Math.max(...ranges.map(({ start, end }) => heightOf(start, end)))).toBe(optimum);
  expect(ranges[0]?.start).toBe(0);
  expect(ranges.at(-1)?.end).toBe(heights.length);
  for (let i = 1; i < ranges.length; i++) expect(ranges[i]?.start).toBe(ranges[i - 1]?.end);
});
