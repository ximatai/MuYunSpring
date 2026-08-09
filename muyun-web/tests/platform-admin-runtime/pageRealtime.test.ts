import { assert, it } from 'vitest';
import {
  createPageBusinessEventHandler,
  createPageDataChangeHandler,
  createPageRecordExternalChangeState,
  createRealtimeRefreshQueue,
  type RealtimeRefreshRun,
} from '@/platform-admin-runtime/pageRealtime.ts';
import type { WebBusinessRealtimeEvent } from '@/web-contracts/index.ts';

it('page business event handler filters by type module and record', async () => {
  const handled: string[] = [];
  const handler = createPageBusinessEventHandler({
    type: 'iam.user.session.collectionChanged',
    moduleAlias: 'iam.user',
    recordId: () => 'user-1',
    handler: (event) => {
      handled.push(event.recordId);
    },
  });

  handler(businessEvent({ recordId: 'user-2' }));
  handler(businessEvent({ type: 'other.event', recordId: 'user-1' }));
  handler(businessEvent({ moduleAlias: 'iam.role', recordId: 'user-1' }));
  handler(businessEvent({ recordId: 'user-1' }));
  await flushPromises();

  assert.deepEqual(handled, ['user-1']);
});

it('page data change handler forwards only matching changes', async () => {
  const handled: string[] = [];
  const handler = createPageDataChangeHandler({
    moduleAlias: 'sales.order',
    recordId: 'order-1',
    handler: (_changeSet, changes) => {
      handled.push(...changes.map((change) => String(change.recordId)));
    },
  });

  handler({
    changeSetId: 'change-set-1',
    changes: [
      { type: 'record-updated', moduleAlias: 'sales.order', recordId: 'order-1' },
      { type: 'record-updated', moduleAlias: 'sales.order', recordId: 'order-2' },
      { type: 'record-updated', moduleAlias: 'iam.user', recordId: 'order-1' },
    ],
  });
  await flushPromises();

  assert.deepEqual(handled, ['order-1']);
});

it('page record external change state marks only external editing record changes', () => {
  let recordId = 'record-1';
  let editing = false;
  let saving = false;
  const state = createPageRecordExternalChangeState({
    moduleAlias: 'iam.employee',
    recordId: () => recordId,
    editing: () => editing,
    saving: () => saving,
  });

  state.handleDataChanges([{ type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'record-1' }]);
  assert.equal(state.externallyChanged.value, false);

  editing = true;
  state.handleDataChanges([{ type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'record-2' }]);
  assert.equal(state.externallyChanged.value, false);

  saving = true;
  state.handleDataChanges([{ type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'record-1' }]);
  assert.equal(state.externallyChanged.value, false);

  saving = false;
  state.handleDataChanges([
    { type: 'collection-changed', moduleAlias: 'iam.employee', recordId: 'record-1' },
    { type: 'record-updated', moduleAlias: 'iam.user', recordId: 'record-1' },
    { type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'record-1' },
  ]);
  assert.equal(state.externallyChanged.value, true);
  assert.equal(state.externalChangedRecordId.value, 'record-1');

  recordId = 'record-2';
  state.clearExternalChanged();
  assert.equal(state.markExternalRecordChanged('record-2'), true);
  assert.equal(state.externalChangedRecordId.value, 'record-2');
});

it('realtime refresh queue coalesces keys in one flush', async () => {
  const runs: Array<RealtimeRefreshRun<string>> = [];
  const queue = createRealtimeRefreshQueue<string>({
    load: (run) => {
      runs.push(run);
    },
  });

  queue.enqueue('user-1');
  queue.enqueue(['user-1', 'user-2']);
  await flushTimers();

  assert.equal(runs.length, 1);
  assert.deepEqual(runs[0].keys, ['user-1', 'user-2']);
});

it('realtime refresh queue marks older runs stale per key', async () => {
  const runs: Array<RealtimeRefreshRun<string>> = [];
  const queue = createRealtimeRefreshQueue<string>({
    load: (run) => {
      runs.push(run);
    },
  });

  queue.enqueue('user-1');
  await waitFor(() => runs.length === 1);
  queue.enqueue('user-1');
  await waitFor(() => runs.length === 2);

  assert.equal(runs[0].isLatest('user-1'), false);
  assert.equal(runs[1].isLatest('user-1'), true);
});

it('realtime refresh queue ignores pending and active runs after dispose', async () => {
  const runs: Array<RealtimeRefreshRun<string>> = [];
  const queue = createRealtimeRefreshQueue<string>({
    load: (run) => {
      runs.push(run);
    },
  });

  queue.enqueue('user-1');
  queue.dispose();
  await flushTimers();
  assert.equal(runs.length, 0);

  const activeQueue = createRealtimeRefreshQueue<string>({
    load: (run) => {
      runs.push(run);
    },
  });
  activeQueue.enqueue('user-2');
  await waitFor(() => runs.length === 1);
  activeQueue.dispose();

  assert.equal(runs[0].active(), false);
  assert.equal(runs[0].isLatest('user-2'), false);
});

it('realtime refresh queue reset clears stale state but keeps queue reusable', async () => {
  const runs: Array<RealtimeRefreshRun<string>> = [];
  const queue = createRealtimeRefreshQueue<string>({
    load: (run) => {
      runs.push(run);
    },
  });

  queue.enqueue('user-1');
  await waitFor(() => runs.length === 1);
  queue.reset();

  assert.equal(runs[0].active(), true);
  assert.equal(runs[0].isLatest('user-1'), false);

  queue.enqueue('user-1');
  await waitFor(() => runs.length === 2);
  assert.equal(runs[1].isLatest('user-1'), true);
});

function businessEvent(overrides: Partial<WebBusinessRealtimeEvent>): WebBusinessRealtimeEvent {
  return {
    type: 'iam.user.session.collectionChanged',
    moduleAlias: 'iam.user',
    recordId: 'user-1',
    ...overrides,
  };
}

async function flushTimers() {
  await new Promise((resolve) => setTimeout(resolve, 5));
  await flushPromises();
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

async function waitFor(predicate: () => boolean) {
  for (let i = 0; i < 20; i += 1) {
    if (predicate()) {
      return;
    }
    await flushTimers();
  }
  assert.equal(predicate(), true);
}
