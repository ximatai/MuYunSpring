import { createLanguageContext } from '@muyun/web-core';
import { describe, expect, it } from 'vitest';
import {
  externalLoginFailureMessage,
  rememberExternalLogin,
  takeExternalLoginReturn,
} from '@/app/externalLoginReturn';

class MemoryStorage implements Storage {
  private values = new Map<string, string>();
  get length() {
    return this.values.size;
  }
  clear() {
    this.values.clear();
  }
  getItem(key: string) {
    return this.values.get(key) ?? null;
  }
  key(index: number) {
    return [...this.values.keys()][index] ?? null;
  }
  removeItem(key: string) {
    this.values.delete(key);
  }
  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
}

const start = (state: string) => ({
  transactionId: `tx-${state}`,
  state,
  authorizationUrl: 'https://idp.test/authorize',
  browserProof: `proof-${state}`,
  expiresAt: '2026-10-24T00:03:00Z',
});

describe('externalLoginReturn', () => {
  it('matches a provider return only to a transaction this browser started, once', () => {
    const storage = new MemoryStorage();
    rememberExternalLogin(
      start('s-1'),
      { providerId: 'welink', purpose: 'login', tenantId: 'tenant-a' },
      storage,
    );
    rememberExternalLogin(start('s-2'), { providerId: 'welink', purpose: 'bind' }, storage);

    expect(takeExternalLoginReturn({ state: 's-2', code: 'c' }, storage)).toEqual({
      pending: { providerId: 'welink', state: 's-2', browserProof: 'proof-s-2', purpose: 'bind' },
      parameters: { state: 's-2', code: 'c' },
    });
    expect(takeExternalLoginReturn({ state: 's-2', code: 'c' }, storage)?.pending).toBeUndefined();
    expect(takeExternalLoginReturn({ state: 's-1', code: 'c' }, storage)?.pending?.tenantId).toBe('tenant-a');
  });

  it('treats a return started elsewhere as foreign and never as this browser’s sign-in', () => {
    const storage = new MemoryStorage();
    rememberExternalLogin(start('mine'), { providerId: 'welink', purpose: 'login' }, storage);

    const foreign = takeExternalLoginReturn({ state: 'attacker-state', code: 'victim-code' }, storage);

    expect(foreign).toEqual({
      pending: undefined,
      parameters: { state: 'attacker-state', code: 'victim-code' },
    });
    expect(takeExternalLoginReturn({ state: 'mine' }, storage)?.pending?.browserProof).toBe('proof-mine');
  });

  it('ignores addresses without a provider state and drops workbench markers from forwarded parameters', () => {
    const storage = new MemoryStorage();
    rememberExternalLogin(start('s-1'), { providerId: 'welink', purpose: 'login' }, storage);

    expect(takeExternalLoginReturn({ _muyunTenantId: 'tenant-a' }, storage)).toBeUndefined();
    expect(
      takeExternalLoginReturn({ state: 's-1', code: ['c'], _muyunTenantId: 'tenant-a' }, storage)?.parameters,
    ).toEqual({ state: 's-1', code: 'c' });
  });

  it('ignores corrupted storage instead of failing the page', () => {
    const storage = new MemoryStorage();
    storage.setItem('muyun.externalLogin.pending', '{not json');

    expect(takeExternalLoginReturn({ state: 's-1' }, storage)?.pending).toBeUndefined();
  });

  it('explains denials without exposing internal codes', () => {
    const language = createLanguageContext({ languageTag: 'zh-CN' });

    expect(
      externalLoginFailureMessage(
        { status: 'denied', purpose: 'login', failure: 'identityNotBound' },
        language,
      ),
    ).toContain('尚未绑定');
    expect(externalLoginFailureMessage({ status: 'expired', purpose: 'login' }, language)).toContain('超时');
    expect(
      externalLoginFailureMessage(
        { status: 'denied', purpose: 'bind', failure: 'providerRejected' },
        language,
      ),
    ).toContain('外部授权未通过');
  });
});
