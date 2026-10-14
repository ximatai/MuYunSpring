import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
export interface CrudRecordListBase {
  id?: string;
  title?: string;
  alias?: string;
  code?: string;
  enabled?: boolean;
}

export function defaultCrudRecordListTitle<TRecord extends CrudRecordListBase>(
  record: TRecord,
  fallback?: string,
  language: LanguageContext = platformLanguageContext,
) {
  return (
    record.title ??
    record.alias ??
    record.code ??
    record.id ??
    fallback ??
    language.t('platform.record.unnamed', '未命名记录')
  );
}

export function defaultCrudRecordListSubtitle<TRecord extends CrudRecordListBase>(record: TRecord) {
  return record.alias ?? record.code ?? record.id;
}

export function defaultCrudRecordListMatches<TRecord extends CrudRecordListBase>(
  record: TRecord,
  normalizedKeyword: string,
  titleOf: (record: TRecord) => string = defaultCrudRecordListTitle,
  subtitleOf: (record: TRecord) => string | undefined = defaultCrudRecordListSubtitle,
) {
  return [titleOf(record), subtitleOf(record), record.alias, record.code, record.id]
    .filter(Boolean)
    .some((item) => String(item).toLowerCase().includes(normalizedKeyword));
}
