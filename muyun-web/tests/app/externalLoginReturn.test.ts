import { createLanguageContext } from '@muyun/web-core';
import { describe, expect, it } from 'vitest';
import {
  externalLoginFailureMessage,
  rememberExternalLogin,
  takeReturningExternalLogin,
  withoutExternalLoginReturn,
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

const start = (transactionId: string) => ({
  transactionId,
  authorizationUrl: 'https://idp.test/authorize',
  browserProof: `proof-${transactionId}`,
  expiresAt: '2026-10-24T00:03:00Z',
});

describe('externalLoginReturn', () => {
  it('redeems only the transaction this browser started, once', () => {
    const storage = new MemoryStorage();
    rememberExternalLogin(start('tx-1'), 'login', storage);
    rememberExternalLogin(start('tx-2'), 'bind', storage);

    expect(takeReturningExternalLogin('?_muyunExternalLogin=tx-2', storage)).toEqual({
      transactionId: 'tx-2',
      browserProof: 'proof-tx-2',
      purpose: 'bind',
    });
    expect(takeReturningExternalLogin('?_muyunExternalLogin=tx-2', storage)).toBeUndefined();
    expect(takeReturningExternalLogin('?_muyunExternalLogin=unknown', storage)).toBeUndefined();
    expect(takeReturningExternalLogin('?_muyunExternalLogin=tx-1', storage)?.purpose).toBe('login');
  });

  it('ignores corrupted storage instead of failing the page', () => {
    const storage = new MemoryStorage();
    storage.setItem('muyun.externalLogin.pending', '{not json');

    expect(takeReturningExternalLogin('?_muyunExternalLogin=tx-1', storage)).toBeUndefined();
  });

  it('removes only the return marker and keeps the locked tenant', () => {
    expect(
      withoutExternalLoginReturn('https://host.test/app/?_muyunTenantId=tenant-a&_muyunExternalLogin=tx-1'),
    ).toBe('https://host.test/app/?_muyunTenantId=tenant-a');
  });

  it('explains denials without exposing internal codes', () => {
    const language = createLanguageContext({ languageTag: 'zh-CN' });

    expect(
      externalLoginFailureMessage(
        { status: 'denied', purpose: 'login', failureCode: 'IDENTITY_NOT_BOUND' },
        language,
      ),
    ).toContain('尚未绑定');
    expect(externalLoginFailureMessage({ status: 'expired', purpose: 'login' }, language)).toContain('超时');
    expect(
      externalLoginFailureMessage(
        { status: 'denied', purpose: 'bind', failureCode: 'SOMETHING_NEW' },
        language,
      ),
    ).not.toContain('SOMETHING_NEW');
  });
});
