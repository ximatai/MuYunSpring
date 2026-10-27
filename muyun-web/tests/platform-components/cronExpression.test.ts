import { expect, it, vi } from 'vitest';
import {
  defaultCronPreset,
  generateCronExpression,
  normalizeCronExpression,
  parseCronPreset,
  validateCronExpression,
} from '@/platform-components/cronExpression';
it.each([
  ['0 0 0 * * *', { kind: 'daily', hour: 0, minute: 0 }],
  ['0 15 9 * * MON,WED', { kind: 'weekly', hour: 9, minute: 15, weekdays: ['MON', 'WED'] }],
  ['0 30 8 31 * *', { kind: 'monthly', hour: 8, minute: 30, day: 31 }],
  ['0 */15 * * * *', { kind: 'minutes', interval: 15 }],
])('round-trips the supported assistant template %s', (expression, fields) => {
  const parsed = parseCronPreset(expression);
  expect(parsed).toMatchObject(fields);
  expect(generateCronExpression(parsed!)).toBe(expression);
});
it.each(['0 0 8 LW * *', '0 0 9 ? * MON#2', '0 0 24 * * *', '0 0 8 0 * *', '0 */7 * * * *'])(
  'does not reinterpret a custom or invalid template %s',
  (expression) => {
    expect(parseCronPreset(expression)).toBeUndefined();
  },
);
it('generates only valid assistant options and does not narrow advanced server syntax', async () => {
  expect(generateCronExpression({ ...defaultCronPreset(), kind: 'weekly', weekdays: [] })).toBeUndefined();
  expect(
    generateCronExpression({ ...defaultCronPreset(), kind: 'weekly', weekdays: ['WED', 'MON', 'WED'] }),
  ).toBe('0 0 0 * * MON,WED');
  expect(normalizeCronExpression('  0\t30  9 * * *  ')).toBe('0 30 9 * * *');
  const request = vi.fn().mockResolvedValue({ valid: true });
  expect(await validateCronExpression({ request }, '0 0 9 ? * MON#2')).toEqual({ valid: true });
  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/platform.schedule/validate-cron',
    body: { expression: '0 0 9 ? * MON#2' },
  });
});
