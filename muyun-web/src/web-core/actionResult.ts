import {
  webDataChangeTypes,
  type WebActionMessage,
  type WebActionResultEnvelope,
  type WebDataChange,
  type WebDataChangeType,
} from '@muyun/web-contracts';

export interface ResolvedWebActionResult {
  message: string;
  messageCode?: string;
  messageArgs?: Record<string, unknown>;
  messageType?: string;
  resultType?: string;
  changeSetId?: string;
  changes: WebDataChange[];
  raw: unknown;
}

export { webDataChangeTypes };

export const webDataChanges = {
  recordCreated(moduleAlias: string, recordId: string, facts: WebDataChangeFacts = {}): WebDataChange {
    return dataChangeOf(webDataChangeTypes.recordCreated, moduleAlias, { ...facts, recordId });
  },
  recordUpdated(moduleAlias: string, recordId: string, facts: WebDataChangeFacts = {}): WebDataChange {
    return dataChangeOf(webDataChangeTypes.recordUpdated, moduleAlias, { ...facts, recordId });
  },
  recordDeleted(moduleAlias: string, recordId: string, facts: WebDataChangeFacts = {}): WebDataChange {
    return dataChangeOf(webDataChangeTypes.recordDeleted, moduleAlias, { ...facts, recordId });
  },
  collectionChanged(moduleAlias: string, facts: WebDataChangeFacts = {}): WebDataChange {
    return dataChangeOf(webDataChangeTypes.collectionChanged, moduleAlias, facts);
  },
};

export type WebDataChangeFacts = Omit<WebDataChange, 'type' | 'moduleAlias'>;

export function withWebActionResultChanges<T>(
  result: T,
  changes: WebDataChange[],
): T & { changes: WebDataChange[] } {
  const existingChanges = actionResultChanges(result);
  const existingKeys = new Set(existingChanges.map(webDataChangeKey));
  const missingChanges = changes.filter((change) => !existingKeys.has(webDataChangeKey(change)));
  if (result && typeof result === 'object') {
    return {
      ...result,
      changes: [...existingChanges, ...missingChanges],
    };
  }
  return {
    message: undefined,
    raw: result,
    changes: missingChanges,
  } as unknown as T & { changes: WebDataChange[] };
}

export function resolveWebActionResultMessage(result: unknown, fallbackMessage = '操作成功') {
  return resolveWebActionResult(result, { fallbackMessage }).message;
}

export function resolveWebActionResult(
  result: unknown,
  options: { fallbackMessage?: string } = {},
): ResolvedWebActionResult {
  const message = actionResultMessage(result);
  return {
    message: message?.text ?? options.fallbackMessage ?? '操作成功',
    messageCode: message?.code,
    messageArgs: message?.messageArgs,
    messageType: message?.type,
    resultType: actionResultType(result),
    changeSetId: actionResultChangeSetId(result),
    changes: actionResultChanges(result),
    raw: result,
  };
}

export function actionResultData<TData>(result: TData | WebActionResultEnvelope<TData>): TData {
  if (!result || typeof result !== 'object' || !('data' in result)) {
    return result as TData;
  }
  return (result as WebActionResultEnvelope<TData>).data;
}

export function webDataChangeKey(change: WebDataChange) {
  return [
    change.type,
    change.moduleAlias,
    stringFact(change.recordId),
    stringFact(change.resourceKey),
    stringFact(change.scope),
  ].join('|');
}

function actionResultMessage(result: unknown): WebActionMessage | undefined {
  if (!result || typeof result !== 'object' || !('message' in result)) {
    return undefined;
  }
  const message = (result as { message?: unknown }).message;
  if (typeof message === 'string' && message.trim()) {
    return { text: message.trim() };
  }
  if (!message || typeof message !== 'object') {
    return undefined;
  }
  const text = (message as { text?: unknown }).text;
  if (typeof text !== 'string' || !text.trim()) {
    return undefined;
  }
  const code = (message as { code?: unknown }).code;
  const type = (message as { type?: unknown }).type;
  return {
    text: text.trim(),
    code: typeof code === 'string' && code.trim() ? code.trim() : undefined,
    type: typeof type === 'string' && type.trim() ? type.trim() : undefined,
    messageArgs:
      'messageArgs' in message &&
      message.messageArgs &&
      typeof message.messageArgs === 'object' &&
      !Array.isArray(message.messageArgs)
        ? (message.messageArgs as Record<string, unknown>)
        : undefined,
  };
}

function actionResultType(result: unknown): string | undefined {
  if (!result || typeof result !== 'object' || !('resultType' in result)) {
    return undefined;
  }
  const resultType = (result as { resultType?: unknown }).resultType;
  return typeof resultType === 'string' && resultType.trim() ? resultType : undefined;
}

function actionResultChanges(result: unknown): WebDataChange[] {
  if (!result || typeof result !== 'object' || !('changes' in result)) {
    return [];
  }
  const changes = (result as { changes?: unknown }).changes;
  if (!Array.isArray(changes)) {
    return [];
  }
  return changes.filter(isWebDataChange);
}

function actionResultChangeSetId(result: unknown): string | undefined {
  if (!result || typeof result !== 'object' || !('changeSetId' in result)) {
    return undefined;
  }
  const changeSetId = (result as { changeSetId?: unknown }).changeSetId;
  return typeof changeSetId === 'string' && changeSetId.trim() ? changeSetId.trim() : undefined;
}

function isWebDataChange(change: unknown): change is WebDataChange {
  if (!change || typeof change !== 'object') {
    return false;
  }
  const type = (change as { type?: unknown }).type;
  const moduleAlias = (change as { moduleAlias?: unknown }).moduleAlias;
  return Boolean(
    typeof type === 'string' && type.trim() && typeof moduleAlias === 'string' && moduleAlias.trim(),
  );
}

function dataChangeOf(
  type: WebDataChangeType,
  moduleAlias: string,
  facts: WebDataChangeFacts,
): WebDataChange {
  return {
    type,
    moduleAlias,
    ...facts,
  };
}

function stringFact(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : '';
}
