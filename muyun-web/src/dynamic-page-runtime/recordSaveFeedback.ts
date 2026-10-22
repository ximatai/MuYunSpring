import { userFacingErrorMessage, type AppError, type LanguageContext } from '@muyun/web-core';

/** Only a proven write rejection belongs to the editable draft; unknown outcomes keep their recovery path. */
export function isRecordSaveRejection(error: AppError): boolean {
  return !error.globallyHandled && [400, 409, 422].includes(error.status ?? 0);
}

/** A child-row target must never be applied to a same-named main-record field. */
export function recordSaveFieldErrors(
  error: AppError | undefined,
  fields: ReadonlyMap<string, unknown>,
  language: LanguageContext,
): Readonly<Record<string, string>> {
  if (!error) return {};
  const message = userFacingErrorMessage(error, language);
  return Object.fromEntries(
    error.targets
      .filter(
        (target) =>
          target.kind?.toLowerCase() === 'field' &&
          target.fieldName &&
          fields.has(target.fieldName) &&
          target.rowIndex == null &&
          (!target.relationAlias || target.relationAlias === 'main'),
      )
      .map((target) => [target.fieldName!, message]),
  );
}
