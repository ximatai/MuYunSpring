import type { AppError } from '@muyun/web-core';

export type AuthenticationRecoveryHandler = (error: AppError, token?: string) => boolean | void;

let authenticationRequiredHandler: AuthenticationRecoveryHandler | undefined;

/** The host owns recovery; token identifies the request, including late failures after a new login. */
export function configureAuthenticationRecovery(handler: AuthenticationRecoveryHandler | undefined) {
  authenticationRequiredHandler = handler;
}

export function recoverAuthentication(error: AppError, token?: string) {
  return authenticationRequiredHandler?.(error, token) ?? false;
}
