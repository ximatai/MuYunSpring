import dayjs, { type Dayjs } from 'dayjs';
import generatePicker from 'ant-design-vue/es/date-picker/generatePicker';
import dayjsConfig from 'ant-design-vue/es/vc-picker/generate/dayjs';
import type { GenerateConfig } from 'ant-design-vue/es/vc-picker/generate';
import { parseDatePickerValue } from './datePickerValue';

/** A wall-clock calendar: vendor calendar arithmetic must not apply the browser's DST rules. */
export const datePickerCalendar: GenerateConfig<Dayjs> = {
  ...dayjsConfig,
  getNow: () => parseDatePickerValue(dayjs().format('YYYY-MM-DDTHH:mm:ss'), 'datetime')!,
  getFixedDate: (value) => dayjs.utc(value),
  locale: {
    ...dayjsConfig.locale,
    parse: (locale, text, formats) => {
      for (const format of formats) {
        const parsed = dayjs.utc(text, format, true);
        if (parsed.isValid())
          return parsed.locale(locale.split('_')[0]!).locale(locale.replace('_', '-').toLowerCase());
      }
      return null;
    },
  },
};

export const CalendarDatePicker = generatePicker<Dayjs>(datePickerCalendar).DatePicker;
