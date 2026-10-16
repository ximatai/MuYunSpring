import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
export type PlatformDateTimePrecision = 'minute' | 'second';

export interface PlatformDateTimeOptions {
  emptyText?: string;
  locale?: string;
  language?: LanguageContext;
  precision?: PlatformDateTimePrecision;
  timeZone?: string;
}

export interface PlatformDateTimeDisplay {
  text: string;
  title: string;
  datetime?: string;
  timeZone?: string;
  valid: boolean;
}

const DEFAULT_EMPTY_TEXT = '-';

export function resolveBrowserTimeZone() {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

export function formatPlatformDateTime(
  value: string | number | Date | null | undefined,
  options: PlatformDateTimeOptions = {},
): PlatformDateTimeDisplay {
  const language = options.language ?? platformLanguageContext;
  const emptyText = options.emptyText ?? DEFAULT_EMPTY_TEXT;
  if (value === null || value === undefined || value === '') {
    return {
      text: emptyText,
      title: emptyText,
      valid: false,
    };
  }

  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    const text = String(value);
    return {
      text,
      title: text,
      valid: false,
    };
  }

  const timeZone = options.timeZone ?? resolveBrowserTimeZone();
  const precision = options.precision ?? 'second';
  const datetime = date.toISOString();
  return {
    text: formatDateParts(date, {
      locale: options.locale ?? language.languageTag.value,
      precision,
      timeZone,
    }),
    title: language.t('platform.datetime.tooltip', 'UTC: {datetime}\n时区: {timeZone}', {
      datetime,
      timeZone,
    }),
    datetime,
    timeZone,
    valid: true,
  };
}

function formatDateParts(
  date: Date,
  options: Required<Pick<PlatformDateTimeOptions, 'locale' | 'precision' | 'timeZone'>>,
) {
  const formatter = new Intl.DateTimeFormat(options.locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: options.precision === 'second' ? '2-digit' : undefined,
    hourCycle: 'h23',
    timeZone: options.timeZone,
  });
  const parts = Object.fromEntries(
    formatter
      .formatToParts(date)
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, part.value]),
  );
  const dateText = `${parts.year}-${parts.month}-${parts.day}`;
  const timeText =
    options.precision === 'second'
      ? `${parts.hour}:${parts.minute}:${parts.second}`
      : `${parts.hour}:${parts.minute}`;
  return `${dateText} ${timeText}`;
}
