import { expect, it } from 'vitest';
import { durationSecondsParts, parseDurationSeconds } from '@/platform-components/durationSeconds';
it.each([
  [259200, '3', 86400],
  [7200, '2', 3600],
  [120, '2', 60],
  [90, '90', 1],
  [0, '0', 1],
])('preserves %s seconds when choosing an initial unit', (seconds, amount, unit) => {
  expect(durationSecondsParts(seconds)).toEqual({ amount, unit });
  expect(parseDurationSeconds(String(amount), Number(unit))).toBe(seconds);
});
it.each([
  ['0.07', 3600, 252],
  ['1.5', 60, 90],
  ['0.1', 1, null],
  ['-1', 86400, null],
  ['2147483647', 1, 2147483647],
  ['2147483648', 1, null],
  ['9007199254740993', 1, null],
  ['Infinity', 1, null],
  ['invalid', 60, null],
  ['', 60, undefined],
  ['2', 7, null],
])('converts %s × %s without rounding or changing the wire type', (amount, unit, expected) => {
  expect(parseDurationSeconds(String(amount), Number(unit))).toBe(expected);
});
