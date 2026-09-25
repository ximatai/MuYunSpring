import { pinyin } from 'pinyin-pro';
import type { MetadataFieldPropertyKind } from './metadataOrchestrationState';

export const PLATFORM_FIELD_NAME_PATTERN = '^[a-z][A-Za-z0-9]{0,62}$';
export const DYNAMIC_RECORD_RESERVED_FIELD_NAMES = new Set([
  'id',
  'tenantId',
  'version',
  'uiConfigId',
  'values',
  'children',
  'attachments',
  'originContext',
  'record',
]);

export function isPlatformFieldName(value: string): boolean {
  return new RegExp(PLATFORM_FIELD_NAME_PATTERN).test(value);
}

export function isDynamicRecordReservedFieldName(value: string): boolean {
  return DYNAMIC_RECORD_RESERVED_FIELD_NAMES.has(value);
}

export function physicalNameOf(fieldName?: string): string {
  return (fieldName ?? '')
    .trim()
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .toLowerCase();
}

export function generatedFieldName(title?: string): string {
  const normalized = (title ?? '').trim();
  if (/[\u3400-\u9fff]/.test(normalized)) {
    const [first, ...rest] = pinyin(normalized, { toneType: 'none', type: 'array' })
      .map((part) => part.replace(/[^a-zA-Z0-9]/g, ''))
      .filter(Boolean);
    if (first)
      return `${first.toLowerCase()}${rest.map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1).toLowerCase()}`).join('')}`;
  }
  const ascii = normalized
    .normalize('NFKD')
    .replace(/[^a-zA-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .toLowerCase();
  if (ascii) {
    const [first, ...rest] = ascii.split('_').filter(Boolean);
    return `${first}${rest.map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1)}`).join('')}`;
  }
  let hash = 0;
  for (const character of normalized) hash = ((hash << 5) - hash + character.codePointAt(0)!) | 0;
  const suffix = Math.abs(hash).toString(36);
  return `field${suffix.charAt(0).toUpperCase()}${suffix.slice(1)}`;
}

/** Default names for newly created business fields; runtime semantics remain metadata-driven. */
export function generatedBusinessFieldName(
  title: string | undefined,
  kind: MetadataFieldPropertyKind,
): string {
  if (!title?.trim()) return '';
  const name = generatedFieldName(title);
  const role = name.charAt(0).toUpperCase() + name.slice(1);
  const candidate =
    kind === 'DICTIONARY'
      ? `dict${role}`
      : kind === 'MODULE_REFERENCE'
        ? `ref${role}Id`
        : /^[a-z]/.test(name)
          ? name
          : `field${role}`;
  return candidate.slice(0, 63);
}

/** Metadata identifiers use lower snake case; they are stable after explicit editing. */
export function generatedMetadataAlias(title: string, maxLength = 63): string {
  if (!title.trim()) return '';
  const alias = physicalNameOf(generatedFieldName(title));
  return (/^[a-z]/.test(alias) ? alias : `entity_${alias}`).slice(0, maxLength);
}
