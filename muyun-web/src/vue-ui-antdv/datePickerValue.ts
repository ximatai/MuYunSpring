import dayjs, { type Dayjs } from 'dayjs';
import customParseFormat from 'dayjs/plugin/customParseFormat';
import utc from 'dayjs/plugin/utc';

dayjs.extend(customParseFormat);
dayjs.extend(utc);

export type UiDatePickerMode = 'date' | 'datetime';
export type UiDatePickerPrecision = 'minute' | 'second';

/** Calendar fields only: offsets and instant conversion belong to the owning platform field. */
export function parseDatePickerValue(
  value: string | null | undefined,
  mode: UiDatePickerMode,
): Dayjs | undefined {
  if (!value) return undefined;
  const formats = mode === 'date' ? ['YYYY-MM-DD'] : ['YYYY-MM-DDTHH:mm:ss', 'YYYY-MM-DDTHH:mm'];
  for (const format of formats) {
    // UTC is a neutral calendar container here, not the meaning of the business value.
    const parsed = dayjs.utc(value, format, true);
    if (parsed.isValid()) return parsed;
  }
  return undefined;
}

export function datePickerWireValue(
  value: unknown,
  mode: UiDatePickerMode,
  precision: UiDatePickerPrecision,
): string | undefined {
  if (value == null) return '';
  if (!dayjs.isDayjs(value) || !value.isValid()) return undefined;
  const calendar = precision === 'minute' ? value.second(0) : value;
  return calendar.format(mode === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss');
}
