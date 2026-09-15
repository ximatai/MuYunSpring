import { describe, expect, it } from 'vitest';
import { createDictionaryRadioFactRequestEpoch } from '@/views/dictionaryRadioFactRequestEpoch';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe('dictionary radio fact request epoch', () => {
  it('keeps facts from the active dictionary binding when an old request finishes last', async () => {
    const epoch = createDictionaryRadioFactRequestEpoch();
    const oldRequest = deferred<{ enabledCandidateCount: number }>();
    const newRequest = deferred<{ enabledCandidateCount: number }>();
    let facts: { enabledCandidateCount: number } | undefined;

    const oldGeneration = epoch.capture();
    const applyOld = oldRequest.promise.then((result) => {
      if (epoch.isCurrent(oldGeneration)) facts = result;
    });

    epoch.invalidate();
    const newGeneration = epoch.capture();
    const applyNew = newRequest.promise.then((result) => {
      if (epoch.isCurrent(newGeneration)) facts = result;
    });

    newRequest.resolve({ enabledCandidateCount: 2 });
    await applyNew;
    oldRequest.resolve({ enabledCandidateCount: 99 });
    await applyOld;

    expect(facts).toEqual({ enabledCandidateCount: 2 });
  });
});
