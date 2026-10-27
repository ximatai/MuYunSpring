import { actionResultData, type HttpClient } from '@muyun/web-core';

export type CronPresetKind = 'daily' | 'weekly' | 'monthly' | 'minutes';
export interface CronPreset {
  kind: CronPresetKind;
  hour: number;
  minute: number;
  weekdays: string[];
  day: number;
  interval: number;
}
export interface CronValidationResult {
  valid: boolean;
  reason?: string | null;
}
export type CronExpressionValidator = (expression: string) => Promise<CronValidationResult>;
export interface CronInputValidity {
  valid: boolean;
  pending?: boolean;
  message?: string;
}
export interface CronExpressionInputProps {
  value?: string | null;
  validator: CronExpressionValidator;
  disabled?: boolean;
  invalid?: boolean;
  ariaLabel?: string;
  showFeedback?: boolean;
}
export const cronWeekdays = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const;
export const cronMinuteIntervals = [1, 5, 10, 15, 20, 30] as const;
export function normalizeCronExpression(value: string) {
  return value.trim().replace(/\s+/g, ' ');
}
export function defaultCronPreset(): CronPreset {
  return { kind: 'daily', hour: 0, minute: 0, weekdays: ['MON'], day: 1, interval: 5 };
}
/** These are assistant templates, never a substitute for the server's Spring Cron parser. */
export function generateCronExpression(preset: CronPreset): string | undefined {
  if (preset.kind === 'minutes')
    return cronMinuteIntervals.some((value) => value === preset.interval)
      ? `0 */${preset.interval} * * * *`
      : undefined;
  if (
    !Number.isInteger(preset.hour) ||
    preset.hour < 0 ||
    preset.hour > 23 ||
    !Number.isInteger(preset.minute) ||
    preset.minute < 0 ||
    preset.minute > 59
  )
    return undefined;
  const time = `0 ${preset.minute} ${preset.hour}`;
  if (preset.kind === 'daily') return `${time} * * *`;
  if (preset.kind === 'monthly')
    return Number.isInteger(preset.day) && preset.day >= 1 && preset.day <= 31
      ? `${time} ${preset.day} * *`
      : undefined;
  if (preset.kind === 'weekly') {
    const days = cronWeekdays.filter((day) => preset.weekdays.includes(day));
    return days.length ? `${time} * * ${days.join(',')}` : undefined;
  }
}
export function parseCronPreset(expression: string): CronPreset | undefined {
  const fields = normalizeCronExpression(expression).split(' ');
  if (fields.length !== 6 || fields[0] !== '0' || fields[4] !== '*') return;
  const preset = defaultCronPreset();
  const interval = fields[1]?.match(/^\*\/(\d+)$/);
  if (interval && fields.slice(2).every((value) => value === '*')) {
    preset.kind = 'minutes';
    preset.interval = Number(interval[1]);
    return generateCronExpression(preset) ? preset : undefined;
  }
  if (!/^\d+$/.test(fields[1]!) || !/^\d+$/.test(fields[2]!)) return;
  preset.minute = Number(fields[1]);
  preset.hour = Number(fields[2]);
  if (fields[3] === '*' && fields[5] === '*') preset.kind = 'daily';
  else if (/^\d+$/.test(fields[3]!) && fields[5] === '*') {
    preset.kind = 'monthly';
    preset.day = Number(fields[3]);
  } else if (fields[3] === '*') {
    const days = fields[5]!.toUpperCase().split(',');
    if (!days.every((day) => cronWeekdays.some((known) => known === day))) return;
    preset.kind = 'weekly';
    preset.weekdays = days;
  } else return;
  return generateCronExpression(preset) ? preset : undefined;
}
export function validateCronExpression(http: HttpClient, expression: string): Promise<CronValidationResult> {
  return http
    .request<CronValidationResult>({
      method: 'POST',
      path: '/platform.schedule/validate-cron',
      body: { expression },
    })
    .then(actionResultData);
}
