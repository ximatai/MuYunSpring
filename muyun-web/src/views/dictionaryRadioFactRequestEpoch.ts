/**
 * Guards asynchronous candidate reads while a kept-alive composer reloads its metadata directory.
 * A field name is stable across a dictionary rebind, so the directory generation—not the name—is
 * the authority for whether an in-flight response may still update radio eligibility.
 */
export function createDictionaryRadioFactRequestEpoch() {
  let generation = 0;

  return {
    invalidate() {
      generation += 1;
    },
    capture() {
      return generation;
    },
    isCurrent(capturedGeneration: number) {
      return capturedGeneration === generation;
    },
  };
}
