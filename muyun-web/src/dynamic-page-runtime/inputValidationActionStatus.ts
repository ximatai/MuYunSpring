import { ref } from 'vue';

export type InputValidationTarget =
  | { kind: 'draft'; fingerprint: string }
  | { kind: 'record'; recordId: string; fingerprint: string };

export interface InputValidationAttempt {
  actionKey: string;
  target: InputValidationTarget;
}

/** Tracks successful validation against the exact input snapshot that was tested. */
export function useInputValidationActionStatus() {
  const drafts = ref(new Map<string, string>());
  const records = ref(new Map<string, Map<string, string>>());

  function begin(actionKey: string, target: InputValidationTarget): InputValidationAttempt {
    if (target.kind === 'draft') {
      const next = new Map(drafts.value);
      next.delete(actionKey);
      drafts.value = next;
    } else {
      const actionRecords = new Map(records.value.get(actionKey));
      actionRecords.delete(target.recordId);
      records.value = new Map(records.value).set(actionKey, actionRecords);
    }
    return { actionKey, target };
  }

  function succeed(attempt: InputValidationAttempt, currentFingerprint?: string) {
    const { actionKey, target } = attempt;
    if (target.kind === 'draft') {
      drafts.value = new Map(drafts.value).set(actionKey, target.fingerprint);
      return;
    }
    if (currentFingerprint !== target.fingerprint) return;
    records.value = new Map(records.value).set(
      actionKey,
      new Map(records.value.get(actionKey)).set(target.recordId, target.fingerprint),
    );
  }

  function isDraftValidated(actionKey: string, fingerprint: string | undefined) {
    return fingerprint != null && drafts.value.get(actionKey) === fingerprint;
  }

  function isRecordValidated(
    actionKey: string,
    recordId: string | undefined,
    fingerprint: string | undefined,
  ) {
    return (
      recordId != null && fingerprint != null && records.value.get(actionKey)?.get(recordId) === fingerprint
    );
  }

  function invalidateRecord(recordId: string) {
    records.value = new Map(
      [...records.value].map(([actionKey, actionRecords]) => {
        const next = new Map(actionRecords);
        next.delete(recordId);
        return [actionKey, next];
      }),
    );
  }

  return { begin, succeed, isDraftValidated, isRecordValidated, invalidateRecord };
}
