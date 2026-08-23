import { assert, it } from 'vitest';
import { useUserSessionRows } from '@/platform-admin-runtime/user/useUserSessionRows.ts';
import type { QueryListRecord } from '@/platform-components/index.ts';
import type { UserSessionView } from '@/web-contracts/index.ts';
import type { HttpRequestOptions, ModuleContext } from '@/web-core/index.ts';

it('loads session actions before treating an initially unknown session permission as a denial', async () => {
  let recordActionsCalls = 0;
  const requests: string[] = [];
  const rows = useUserSessionRows({
    context: {
      moduleAlias: 'iam.user',
      can: () => undefined,
      recordActions: async () => {
        recordActionsCalls += 1;
        return { recordId: 'user-1', actions: [{ actionCode: 'sessions', available: true }] };
      },
      http: {
        request: <T>(request: HttpRequestOptions) => {
          requests.push(request.path);
          return Promise.resolve([session('session-1')] as T);
        },
      },
    } as unknown as ModuleContext<QueryListRecord>,
    source: 'test-user-expansion',
  });

  rows.handleUserRowExpand({ id: 'user-1' }, true);
  await waitFor(() => requests.length === 1);

  assert.equal(recordActionsCalls, 1);
  assert.deepEqual(requests, ['/iam.user/user-1/sessions']);
  assert.deepEqual(rows.userSessionState('user-1').records, [session('session-1')]);
});

function session(id: string): UserSessionView {
  return {
    id,
    userId: 'user-1',
    issuedAt: '2026-01-01T00:00:00Z',
    expiresAt: '2026-01-02T00:00:00Z',
    current: false,
  };
}

async function waitFor(predicate: () => boolean) {
  for (let index = 0; index < 20; index += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  assert.equal(predicate(), true);
}
