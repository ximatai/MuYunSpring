import { describe, expect, it } from 'vitest';
import { datePickerCalendar } from '@/vue-ui-antdv/datePickerCalendar';
import { parseDatePickerValue, datePickerWireValue } from '@/vue-ui-antdv/datePickerValue';

describe('date picker calendar values', () => {
  it('preserves calendar dates and local wall times without adding offsets', () => {
    for (const value of ['2026-03-08T02:30:17', '2026-11-01T01:30:17']) {
      expect(datePickerWireValue(parseDatePickerValue(value, 'datetime'), 'datetime', 'second')).toBe(value);
    }
    expect(datePickerWireValue(parseDatePickerValue('2024-02-29', 'date'), 'date', 'second')).toBe(
      '2024-02-29',
    );
  });
  it('rejects rollover dates and instant strings instead of silently changing their meaning', () => {
    for (const value of ['2026-02-29', '2026-13-01', '2026-01-32'])
      expect(parseDatePickerValue(value, 'date')).toBeUndefined();
    for (const value of ['2026-01-01T25:00:00', '2026-01-01T12:00:00Z', '2026-01-01T12:00:00+08:00'])
      expect(parseDatePickerValue(value, 'datetime')).toBeUndefined();
  });
  it('accepts minute input, normalizes explicit precision and clears to an empty string', () => {
    expect(
      datePickerWireValue(parseDatePickerValue('2026-10-20T14:15', 'datetime'), 'datetime', 'second'),
    ).toBe('2026-10-20T14:15:00');
    expect(
      datePickerWireValue(parseDatePickerValue('2026-10-20T14:15:37', 'datetime'), 'datetime', 'minute'),
    ).toBe('2026-10-20T14:15:00');
    expect(datePickerWireValue(null, 'date', 'second')).toBe('');
    expect(datePickerWireValue('2026-10-20', 'date', 'second')).toBeUndefined();
  });
});

it('uses neutral calendar arithmetic for typed and selected dates, including DST gaps', () => {
  const calendar = datePickerCalendar.locale.parse('en_US', '2026-03-08 02:30:17', ['YYYY-MM-DD HH:mm:ss'])!;
  expect(calendar.format('YYYY-MM-DDTHH:mm:ss')).toBe('2026-03-08T02:30:17');
  expect(calendar.isUTC()).toBe(true);
  expect(datePickerCalendar.addDate(calendar, 1).format('HH:mm:ss')).toBe('02:30:17');
});
