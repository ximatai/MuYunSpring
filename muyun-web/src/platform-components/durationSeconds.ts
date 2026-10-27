/** Public duration editor contract. Invalid raw values remain visible and emit error-change. */
export interface DurationSecondsInputProps {
  value?: unknown;
  disabled?: boolean;
  invalid?: boolean;
  ariaLabel?: string;
}

/** Integer seconds remain the sole wire value; unit selection is editor state. */
export const durationSecondsUnits = [
  { seconds: 86400, key: 'platform.duration.days', label: '天' },
  { seconds: 3600, key: 'platform.duration.hours', label: '小时' },
  { seconds: 60, key: 'platform.duration.minutes', label: '分钟' },
  { seconds: 1, key: 'platform.duration.seconds', label: '秒' },
] as const;

export function durationSecondsParts(value: unknown) {
  if (value == null) return { amount: '', unit: 86400 };
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0)
    return { amount: String(value), unit: 1 };
  const unit = durationSecondsUnits.find((unit) => value > 0 && value % unit.seconds === 0)?.seconds ?? 1;
  return { amount: String(value / unit), unit };
}

/** null means invalid; undefined means an explicitly cleared optional value. */
export function parseDurationSeconds(amount: string, unit: number): number | null | undefined {
  if (!amount.trim()) return undefined;
  if (!durationSecondsUnits.some((candidate) => candidate.seconds === unit)) return null;
  const text = amount.trim();
  if (!/^\+?(?:\d+(?:\.\d*)?|\.\d+)$/.test(text)) return null;
  const [whole = '', fraction = ''] = text.replace(/^\+/, '').split('.');
  const divisor = 10n ** BigInt(fraction.length);
  const scaled = BigInt((whole || '0') + fraction) * BigInt(unit);
  if (scaled % divisor !== 0n) return null;
  const seconds = scaled / divisor;
  return seconds <= 2147483647n ? Number(seconds) : null;
}
