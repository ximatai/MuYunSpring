import { afterEach, describe, expect, it, vi } from 'vitest';
import { createUuid } from '@muyun/web-core';

const secureRandom = globalThis.crypto.getRandomValues.bind(globalThis.crypto);
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

afterEach(() => vi.unstubAllGlobals());

describe('createUuid', () => {
  it('uses native UUID generation when available', () => {
    const randomUUID = vi.fn(() => '31c837d3-d8b3-4891-9698-59483fc3eb3f');
    vi.stubGlobal('crypto', { randomUUID });
    expect(createUuid()).toBe('31c837d3-d8b3-4891-9698-59483fc3eb3f');
    expect(randomUUID).toHaveBeenCalledOnce();
  });

  it('creates distinct valid UUIDs from secure randomness without randomUUID', () => {
    vi.stubGlobal('crypto', { getRandomValues: secureRandom });
    const values = Array.from({ length: 64 }, () => createUuid());
    expect(values.every((value) => uuidV4.test(value))).toBe(true);
    expect(new Set(values).size).toBe(values.length);
  });

  it('rejects environments without secure randomness', () => {
    vi.stubGlobal('crypto', undefined);
    expect(() => createUuid()).toThrow('Secure random UUID generation is unavailable');
  });
});
