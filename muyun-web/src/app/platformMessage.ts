import { platformLanguageContext, type LanguageContext, type PlatformErrorCode } from '@muyun/web-core';

/** Authentication recovery consumes the same language scope as the application shell. */
export function platformMessage(
  code: PlatformErrorCode,
  fallback: string,
  language: LanguageContext = platformLanguageContext,
) {
  return language.t(`platform.auth.${code}`, fallback);
}
