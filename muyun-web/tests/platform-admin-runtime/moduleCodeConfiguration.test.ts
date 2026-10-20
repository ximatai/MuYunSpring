import { describe, expect, it, vi } from 'vitest';
import type { ModuleContext } from '@muyun/web-core';
import {
  activateModuleCodeCatalog,
  canConfigureModuleCodes,
  moduleCodeCatalog,
} from '@/platform-admin-runtime/moduleCodeConfiguration';
const { subscribe } = vi.hoisted(() => ({ subscribe: vi.fn(() => ({ unsubscribe: vi.fn() })) }));
vi.mock('@/platform-admin-runtime/realtime', () => ({ subscribeAppDataChanges: subscribe }));
const contextOf = (request: unknown) => ({ http: { request } }) as ModuleContext<Record<string, unknown>>;

describe('module code configuration availability', () => {
  it('isolates capability facts by page context and fails closed after a rejected refresh', async () => {
    const request = vi.fn().mockResolvedValue({ configurable: true });
    const left = contextOf(request);
    const right = contextOf(vi.fn().mockResolvedValue({ configurable: false }));
    await Promise.all([moduleCodeCatalog(left).refresh('demo'), moduleCodeCatalog(right).refresh('demo')]);
    expect(canConfigureModuleCodes(left, 'demo')).toBe(true);
    expect(canConfigureModuleCodes(right, 'demo')).toBe(false);
    request.mockRejectedValueOnce(new Error('forbidden'));
    await moduleCodeCatalog(left).refresh('demo');
    expect(canConfigureModuleCodes(left, 'demo')).toBe(false);
    expect(moduleCodeCatalog(left).states.value.demo!.phase).toBe('failed');
  });
  it('ignores obsolete catalog requests and disposes the realtime subscription', async () => {
    let resolve!: (targets: unknown) => void;
    const request = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((done) => {
            resolve = done;
          }),
      )
      .mockResolvedValue({ configurable: false });
    const module = contextOf(request);
    const dispose = activateModuleCodeCatalog(module);
    const catalog = moduleCodeCatalog(module);
    const first = catalog.refresh('demo');
    await catalog.refresh('demo');
    resolve({ configurable: true });
    await first;
    expect(canConfigureModuleCodes(module, 'demo')).toBe(false);
    dispose();
    expect(subscribe.mock.results.at(-1)!.value.unsubscribe).toHaveBeenCalledOnce();
  });
});
