import type { IMessage } from '@stomp/stompjs';
import { assert, expect, it } from 'vitest';
import { computed, nextTick } from 'vue';
import {
  AppError,
  configureModuleContext,
  createAuthClient,
  createLoginContextClient,
  createHttpClient,
  withHttpHeaders,
  createMenuClient,
  createPageBootstrapClient,
  createModuleContext,
  createModuleTreeContext,
  createModuleCrudClient,
  createModuleTreeClient,
  createStaticModuleCrudClient,
  createStaticModuleTreeClient,
  createReferenceResolveClient,
  canQueryRecycleBin,
  hasRecycleBinAbility,
  normalizeError,
  platformErrorCodes,
  resolveGlobalErrorPresentation,
  actionResultData,
  connectRealtimeBusinessEvents,
  connectRealtimeBusinessNotifications,
  connectRealtimeDataChanges,
  connectRealtimeUserNotifications,
  createDataChangeDispatcher,
  webDataChanges,
  createRealtimeClient,
  invokeBusinessNotificationRecordAction,
  contextDataChangeChannel,
  imConversationMessageChannel,
  imMessageSendCommand,
  isHttpStreamClient,
  moduleDataChangeChannel,
  organizationPublicDataChangeChannel,
  organizationPublicNotificationChannel,
  recordDataChangeChannel,
  realtimeDestinations,
  resourceDataChangeChannel,
  resourceRecordDataChangeChannel,
  sessionActivityCommand,
  tenantPublicDataChangeChannel,
  tenantPublicNotificationChannel,
  userBusinessEventChannel,
  userBusinessNotificationChannel,
  userImMessageChannel,
  userNotificationChannel,
  withWebActionResultChanges,
  type HttpClient,
  type StompClientAdapter,
  type StompClientFactoryOptions,
  type StompSubscriptionLike,
} from '@/web-core/index.ts';
import { createMenuTab, getMenuNavigationTarget } from '@/platform-workbench/menuNavigation.ts';

async function expectRejected(
  action: () => Promise<unknown>,
  predicate?: (error: unknown) => boolean,
): Promise<void> {
  try {
    await action();
  } catch (error) {
    expect(predicate?.(error) ?? true).toBe(true);
    return;
  }

  expect.fail('Expected promise to reject');
}

it('adds a fixed page context header without allowing a request to replace it', async () => {
  const requests: Array<{ path: string; headers?: Record<string, string> }> = [];
  const base: HttpClient = {
    async request(options) {
      requests.push(options);
      return undefined as never;
    },
  };

  await withHttpHeaders(base, { 'X-MuYun-Menu-Id': 'menu.system-user' }).request({
    path: '/iam.user/query',
    headers: {
      'X-MuYun-Menu-Id': 'forged',
      'x-muyun-menu-id': 'forged-lowercase',
      'X-Caller': 'test',
    },
  });

  assert.deepEqual(requests, [
    {
      path: '/iam.user/query',
      headers: { 'X-MuYun-Menu-Id': 'menu.system-user', 'X-Caller': 'test' },
    },
  ]);
});

it('resolves page context headers for each request', async () => {
  const requests: Array<{ path: string; headers?: Record<string, string> }> = [];
  const base: HttpClient = {
    async request(options) {
      requests.push(options);
      return undefined as never;
    },
  };
  let context = '{"scheme":"scheme-a"}';
  const client = withHttpHeaders(base, () => ({ 'X-MuYun-Page-Context': context }));

  await client.request({ path: '/platform.menu/view/menu-a' });
  context = '{"scheme":"scheme-b"}';
  await client.request({ path: '/platform.menu/remove/menu-b' });

  assert.deepEqual(requests, [
    {
      path: '/platform.menu/view/menu-a',
      headers: { 'X-MuYun-Page-Context': '{"scheme":"scheme-a"}' },
    },
    {
      path: '/platform.menu/remove/menu-b',
      headers: { 'X-MuYun-Page-Context': '{"scheme":"scheme-b"}' },
    },
  ]);
});

it('limits page context headers to the owning module requests', async () => {
  const requests: Array<{ path: string; headers?: Record<string, string> }> = [];
  const base: HttpClient = {
    async request(options) {
      requests.push(options);
      return undefined as never;
    },
  };
  const client = withHttpHeaders(
    base,
    { 'X-MuYun-Menu-Id': 'menu.user' },
    (request) => request.path === '/iam.user' || request.path.startsWith('/iam.user/'),
  );

  await client.request({ path: '/iam.user/query' });
  await client.request({ path: '/iam.tenant/reference/query' });

  assert.deepEqual(requests, [
    { path: '/iam.user/query', headers: { 'X-MuYun-Menu-Id': 'menu.user' } },
    { path: '/iam.tenant/reference/query' },
  ]);
});

it('business notification record action uses the standard module action and record path', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return Response.json({ data: { accepted: true } });
  };

  try {
    await invokeBusinessNotificationRecordAction(createHttpClient({ baseUrl: 'http://api.local' }), {
      kind: 'record',
      key: 'reject',
      label: '拒绝',
      moduleAlias: 'mr.remote_support',
      recordId: 'support-1',
      actionCode: 'rejectKnowledge',
      arguments: { reason: '不采纳' },
      danger: true,
      dismissOnSuccess: true,
    });
    assert.equal(requests[0].url, 'http://api.local/mr.remote_support/rejectKnowledge/support-1');
    assert.deepEqual(await requests[0].json(), { payload: { reason: '不采纳' } });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('reference resolve client addresses the source field contract', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return Response.json({
      status: 'OK',
      mode: 'QUERY',
      options: [],
      results: [],
      offset: 0,
      limit: 20,
      total: 0,
    });
  };

  try {
    await createReferenceResolveClient(
      createHttpClient({ baseUrl: 'http://api.local' }),
      'crm.contract',
    ).resolve('customerId', { fuzzy: '星云', page: { pageNum: 1, pageSize: 20 } });

    assert.equal(requests[0].url, 'http://api.local/crm.contract/references/customerId/resolve');
    assert.deepEqual(await requests[0].json(), {
      fuzzy: '星云',
      page: { pageNum: 1, pageSize: 20 },
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('reference resolve client honours a server-issued isolated resolve path', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return Response.json({
      status: 'OK',
      mode: 'QUERY',
      options: [],
      results: [],
      offset: 0,
      limit: 20,
      total: 0,
    });
  };

  try {
    await createReferenceResolveClient(
      createHttpClient({ baseUrl: 'http://api.local' }),
      'iam.department',
      '/platform.module/iam.department/references/organizationId/resolve',
    ).resolve('organizationId', { fuzzy: '总部' });

    assert.equal(
      requests[0].url,
      'http://api.local/platform.module/iam.department/references/organizationId/resolve',
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('business notifications have an isolated typed realtime channel', () => {
  assert.equal(userBusinessNotificationChannel.destination, '/user/queue/platform/business-notifications');
  assert.equal(userBusinessNotificationChannel.type, 'platform.business-notification');
  assert.equal(typeof connectRealtimeBusinessNotifications, 'function');
});

it('menu client normalizes backend enum values before workbench navigation', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    Response.json({
      records: [
        {
          record: {
            id: 'todo-board',
            schemeId: 'default',
            title: '待办事项',
            enabled: true,
            entryType: 'ROUTE',
            openMode: 'TAB',
            moduleAlias: 'demo.todo_item',
            route: '/app/todo',
          },
          children: [],
        },
      ],
    });

  try {
    const response = await createMenuClient(createHttpClient({ baseUrl: 'http://api.local' })).mine();
    const menu = response.records[0]?.record;
    if (!menu) throw new Error('Expected a menu record.');
    const target = getMenuNavigationTarget(menu);

    assert.equal(menu.openMode, 'tab');
    assert.deepEqual(target, {
      menuId: 'todo-board',
      menuType: 'route',
      openMode: 'tab',
      moduleAlias: 'demo.todo_item',
      route: '/app/todo',
      entryParamsJson: undefined,
    });
    if (!target) throw new Error('Expected a navigation target.');
    const tab = createMenuTab(menu, target);
    if (!tab) throw new Error('Expected a menu tab.');
    if (!tab.pageDescriptor) throw new Error('Expected a page descriptor.');
    assert.equal(tab.pageDescriptor.pageType, 'platform-route');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('page bootstrap client reads the permission-scoped WEB entry for a menu node', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return Response.json({
      entry: {
        menuId: 'customer-menu',
        moduleAlias: 'crm.customer',
        pageMode: 'LIST',
        defaultUiConfigId: 'customer-list',
        defaultQueryTemplateId: 'active-customers',
      },
      clientType: 'WEB',
      mainEntityAlias: 'customer',
      resolvedConfig: { uiFields: [], queryItems: [] },
      openApiPath: '/crm.customer/openapi',
    });
  };

  try {
    const bootstrap = await createPageBootstrapClient(
      createHttpClient({ baseUrl: 'http://api.local' }),
    ).byMenu('customer-menu');

    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, 'http://api.local/platform.menu/customer-menu/entry?clientType=WEB');
    assert.equal(bootstrap.entry.defaultQueryTemplateId, 'active-customers');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('page bootstrap client rejects blank menu identities before issuing a request', async () => {
  await expectRejected(
    () => createPageBootstrapClient(createHttpClient()).byMenu('   '),
    (error) => error instanceof Error && error.message === 'Page bootstrap requires a menuId',
  );
});

it('auth logout posts bearer token to backend logout endpoint', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return new Response(null, { status: 200 });
  };

  try {
    const authClient = createAuthClient(createHttpClient({ baseUrl: 'http://api.local' }));

    await authClient.logout('token-1');

    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, 'http://api.local/iam.auth/logout');
    assert.equal(requests[0].method, 'POST');
    assert.equal(requests[0].headers.get('Authorization'), 'Bearer token-1');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('auth client resolves the public login context for a URL-locked tenant', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return Response.json({
      tenantId: 'tenant-a',
      branding: { title: '租户 A', subtitle: '租户专属工作台' },
    });
  };

  try {
    const context = await createLoginContextClient(
      createHttpClient({ baseUrl: 'http://api.local' }),
    ).loginContext('tenant-a');

    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, 'http://api.local/iam.auth/login-context?tenantId=tenant-a');
    assert.equal(context.branding?.title, '租户 A');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('auth change own password posts bearer token to backend endpoint', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return new Response(null, { status: 200 });
  };

  try {
    const authClient = createAuthClient(createHttpClient({ baseUrl: 'http://api.local' }));

    await authClient.changeOwnPassword(
      { currentPassword: 'old-secret', newPassword: 'new-secret' },
      'token-1',
    );

    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, 'http://api.local/iam.auth/changeOwnPassword');
    assert.equal(requests[0].method, 'POST');
    assert.equal(requests[0].headers.get('Authorization'), 'Bearer token-1');
    assert.deepEqual(await requests[0].json(), {
      currentPassword: 'old-secret',
      newPassword: 'new-secret',
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client sends platform trace header', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return Response.json({ ok: true });
  };

  try {
    const http = createHttpClient({ baseUrl: 'http://api.local', traceId: 'trace-client' });

    await http.request({ path: '/platform.ping' });

    assert.equal(requests[0].headers.get('X-MuYun-Trace-Id'), 'trace-client');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client requests event-stream media type for authenticated streams', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return new Response('event: complete\n\ndata: {}\n\n', {
      headers: { 'Content-Type': 'text/event-stream' },
    });
  };

  try {
    const http = createHttpClient({ baseUrl: 'http://api.local', token: 'test-token' });

    const stream = await http.stream({ path: '/mr.device/device-1/agent-chat/start/stream', method: 'POST' });

    assert.ok(stream);
    assert.equal(requests[0].headers.get('Accept'), 'text/event-stream');
    assert.equal(requests[0].headers.get('Authorization'), 'Bearer test-token');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('header-scoped streaming client preserves stream capability and controlled page context', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Request(input, init));
    return new Response('event: complete\n\ndata: {}\n\n', {
      headers: { 'Content-Type': 'text/event-stream' },
    });
  };

  try {
    const http = withHttpHeaders(
      createHttpClient({ baseUrl: 'http://api.local' }),
      {
        'X-MuYun-Menu-Id': 'menu.device',
        'X-MuYun-Page-Context': '{"device":"device-1"}',
      },
      (request) => request.path.startsWith('/mr.device/'),
    );

    assert.equal(isHttpStreamClient(http), true);
    const stream = await http.stream({
      path: '/mr.device/device-1/agent-chat/start/stream',
      method: 'POST',
      headers: { 'x-muyun-menu-id': 'forged-lowercase' },
    });

    assert.ok(stream);
    assert.equal(requests[0].headers.get('Accept'), 'text/event-stream');
    assert.equal(requests[0].headers.get('X-MuYun-Menu-Id'), 'menu.device');
    assert.equal(requests[0].headers.get('X-MuYun-Page-Context'), '{"device":"device-1"}');

    await http.stream({ path: '/iam.user/session/stream' });

    assert.equal(requests[1].headers.get('X-MuYun-Menu-Id'), null);
    assert.equal(requests[1].headers.get('X-MuYun-Page-Context'), null);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client keeps custom request-only clients compatible with optional streaming', () => {
  const customClient: HttpClient = {
    request: async <TResponse>() => ({ ok: true }) as TResponse,
  };

  assert.equal(isHttpStreamClient(customClient), false);
  assert.equal(isHttpStreamClient(createHttpClient()), true);
});

it('http client maps malformed stream error responses to an AppError with response diagnostics', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response('{not-json', {
      status: 502,
      headers: { 'Content-Type': 'application/json', 'X-MuYun-Trace-Id': 'trace-stream-malformed' },
    });

  try {
    await expectRejected(
      () => createHttpClient().stream({ path: '/mr.device/device-1/agent-chat/start/stream' }),
      (error) => {
        assert.equal(error instanceof AppError, true);
        const appError = error as AppError;
        assert.equal(appError.code, platformErrorCodes.httpError);
        assert.equal(appError.status, 502);
        assert.equal(appError.traceId, 'trace-stream-malformed');
        assert.match(String(appError.details?.cause), /JSON/);
        return true;
      },
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client delegates an empty unauthorized stream response to the application boundary', async () => {
  const originalFetch = globalThis.fetch;
  const recovered: AppError[] = [];
  globalThis.fetch = async () =>
    new Response(null, { status: 401, headers: { 'X-MuYun-Trace-Id': 'trace-stream-401' } });

  try {
    const http = createHttpClient({
      onAuthenticationRequired: (error) => {
        recovered.push(error);
        return true;
      },
    });

    await expectRejected(() => http.stream({ path: '/mr.device/device-1/agent-chat/start/stream' }));

    assert.equal(recovered.length, 1);
    assert.equal(recovered[0].code, platformErrorCodes.httpError);
    assert.equal(recovered[0].status, 401);
    assert.equal(recovered[0].traceId, 'trace-stream-401');
    assert.equal(recovered[0].globallyHandled, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client delegates expired authentication to the application boundary', async () => {
  const originalFetch = globalThis.fetch;
  const recovered: AppError[] = [];
  globalThis.fetch = async () =>
    Response.json(
      { code: 'AUTH_REQUIRED', status: 401, message: 'current user context is not available' },
      { status: 401 },
    );

  try {
    const http = createHttpClient({
      baseUrl: 'http://api.local',
      onAuthenticationRequired: (error) => {
        recovered.push(error);
        return true;
      },
    });

    await expectRejected(() => http.request({ path: '/iam.auth/context' }));

    assert.equal(recovered.length, 1);
    assert.equal(recovered[0].code, platformErrorCodes.authRequired);
    assert.equal(recovered[0].globallyHandled, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client does not recover a failed login as an expired session', async () => {
  const originalFetch = globalThis.fetch;
  let recoveries = 0;
  globalThis.fetch = async () =>
    Response.json(
      { code: 'LOGIN_BAD_CREDENTIALS', status: 401, message: 'invalid username or password' },
      { status: 401 },
    );

  try {
    const http = createHttpClient({
      baseUrl: 'http://api.local',
      onAuthenticationRequired: () => {
        recoveries += 1;
      },
    });

    await expectRejected(() => http.request({ path: '/iam.auth/login', method: 'POST' }));

    assert.equal(recoveries, 0);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client prefers backend action message on error responses', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    Response.json(
      {
        traceId: 'trace-business',
        code: 'VALIDATION_FAILED',
        status: 422,
        message: 'fallback validation message',
        actionMessage: {
          code: 'iam.employee-account.username-occupied',
          text: '登录账号已被占用',
          type: 'WARNING',
        },
      },
      { status: 422, headers: { 'X-MuYun-Trace-Id': 'trace-header' } },
    );

  try {
    const http = createHttpClient({ baseUrl: 'http://api.local' });

    await expectRejected(
      () => http.request({ path: '/iam.employee/employee-1/account/provision', method: 'POST' }),
      (error) => {
        assert.equal(error instanceof AppError, true);
        const appError = error as AppError;
        assert.equal(appError.status, 422);
        assert.equal(appError.traceId, 'trace-business');
        assert.equal(appError.code, 'iam.employee-account.username-occupied');
        assert.equal(appError.message, '登录账号已被占用');
        assert.deepEqual(appError.actionMessage, {
          code: 'iam.employee-account.username-occupied',
          text: '登录账号已被占用',
          type: 'WARNING',
        });
        return true;
      },
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('web action result changes keep backend change when local fact duplicates it', () => {
  const result = withWebActionResultChanges(
    {
      message: '已保存',
      changes: [webDataChanges.collectionChanged('platform.dictionary', { resourceKey: 'category' })],
    },
    [webDataChanges.collectionChanged('platform.dictionary', { resourceKey: 'category' })],
  );

  assert.deepEqual(result.changes, [
    webDataChanges.collectionChanged('platform.dictionary', { resourceKey: 'category' }),
  ]);
});

it('web action result changes preserve same type with different resource key', () => {
  const result = withWebActionResultChanges(
    {
      message: '已保存',
      changes: [webDataChanges.collectionChanged('platform.dictionary', { resourceKey: 'category' })],
    },
    [webDataChanges.collectionChanged('platform.dictionary', { resourceKey: 'item' })],
  );

  assert.deepEqual(result.changes, [
    webDataChanges.collectionChanged('platform.dictionary', { resourceKey: 'category' }),
    webDataChanges.collectionChanged('platform.dictionary', { resourceKey: 'item' }),
  ]);
});

it('web action result changes include scope in dedupe key', () => {
  const result = withWebActionResultChanges(
    {
      message: '已保存',
      changes: [webDataChanges.collectionChanged('iam.employee', { scope: 'left-pane' })],
    },
    [
      webDataChanges.collectionChanged('iam.employee', { scope: 'right-pane' }),
      webDataChanges.collectionChanged('iam.employee', { scope: 'left-pane' }),
    ],
  );

  assert.deepEqual(result.changes, [
    webDataChanges.collectionChanged('iam.employee', { scope: 'left-pane' }),
    webDataChanges.collectionChanged('iam.employee', { scope: 'right-pane' }),
  ]);
});

it('web action result changes separate records inside the same module', () => {
  const result = withWebActionResultChanges(
    {
      message: '已保存',
      changes: [webDataChanges.recordUpdated('iam.employee', 'emp-1')],
    },
    [webDataChanges.recordUpdated('iam.employee', 'emp-2')],
  );

  assert.deepEqual(result.changes, [
    webDataChanges.recordUpdated('iam.employee', 'emp-1'),
    webDataChanges.recordUpdated('iam.employee', 'emp-2'),
  ]);
});

it('web action result data unwraps backend action envelope', () => {
  assert.deepEqual(
    actionResultData({
      data: { id: 'user-1', username: 'alice' },
      message: { code: 'iam.user.created', text: '账号已创建', type: 'SUCCESS' },
      changeSetId: 'change-set-1',
      changes: [{ type: 'record-created', moduleAlias: 'iam.user', recordId: 'user-1' }],
    }),
    { id: 'user-1', username: 'alice' },
  );
  assert.deepEqual(actionResultData({ id: 'user-1', username: 'alice' }), {
    id: 'user-1',
    username: 'alice',
  });
});

it('data change dispatcher deduplicates change sets by id', async () => {
  const dispatcher = createDataChangeDispatcher();
  const handled: string[] = [];
  dispatcher.subscribe((changeSet) => {
    handled.push(changeSet.changeSetId);
  });

  assert.equal(
    await dispatcher.dispatch({
      changeSetId: 'change-set-1',
      changes: [{ type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'emp-1' }],
    }),
    true,
  );
  assert.equal(
    await dispatcher.dispatch({
      changeSetId: 'change-set-1',
      changes: [{ type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'emp-1' }],
    }),
    false,
  );
  dispatcher.markHandled('change-set-2');
  assert.equal(
    await dispatcher.dispatch({
      changeSetId: 'change-set-2',
      changes: [{ type: 'record-deleted', moduleAlias: 'iam.employee', recordId: 'emp-1' }],
    }),
    false,
  );
  assert.deepEqual(handled, ['change-set-1']);
});

it('data change dispatcher isolates handler errors', async () => {
  const dispatcher = createDataChangeDispatcher();
  const handled: string[] = [];
  dispatcher.subscribe(() => {
    throw new Error('handler failed');
  });
  dispatcher.subscribe((changeSet) => {
    handled.push(changeSet.changeSetId);
  });

  assert.equal(
    await dispatcher.dispatch({
      changeSetId: 'change-set-1',
      changes: [{ type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'emp-1' }],
    }),
    true,
  );
  assert.equal(
    await dispatcher.dispatch({
      changeSetId: 'change-set-1',
      changes: [{ type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'emp-1' }],
    }),
    false,
  );
  assert.deepEqual(handled, ['change-set-1']);
});

it('data change dispatcher bounds handled change set ids', async () => {
  const dispatcher = createDataChangeDispatcher({ maxHandledChangeSetIds: 2 });
  const handled: string[] = [];
  dispatcher.subscribe((changeSet) => {
    handled.push(changeSet.changeSetId);
  });

  for (const changeSetId of ['change-set-1', 'change-set-2', 'change-set-3', 'change-set-1']) {
    await dispatcher.dispatch({
      changeSetId,
      changes: [{ type: 'record-updated', moduleAlias: 'iam.employee', recordId: changeSetId }],
    });
  }

  assert.deepEqual(handled, ['change-set-1', 'change-set-2', 'change-set-3', 'change-set-1']);
});

it('realtime client keeps the API base path, sends bearer header and restores subscriptions after reconnect', async () => {
  let factoryOptions: StompClientFactoryOptions | undefined;
  const stomp = new FakeStompClient();
  const realtime = createRealtimeClient({
    baseUrl: 'https://api.local/base',
    token: 'token-1',
    clientFactory: (options) => {
      factoryOptions = options;
      stomp.options = options;
      return stomp;
    },
  });
  const received: unknown[] = [];

  realtime.subscribe(
    { destination: '/user/queue/platform/data-changes', type: 'platform.data-change' },
    (payload) => {
      received.push(payload);
    },
  );
  await realtime.connect();
  stomp.connect();

  assert.equal(factoryOptions?.brokerURL, 'wss://api.local/base/ws/platform');
  assert.equal(factoryOptions?.connectHeaders.Authorization, 'Bearer token-1');
  assert.equal(stomp.subscribeCalls, 1);

  stomp.emit(
    '/user/queue/platform/data-changes',
    JSON.stringify({
      id: 'message-1',
      type: 'platform.data-change',
      occurredAt: '2026-07-15T10:00:00Z',
      payload: { changeSetId: 'change-set-1', changes: [] },
    }),
  );
  stomp.connect();

  assert.deepEqual(received, [{ changeSetId: 'change-set-1', changes: [] }]);
  assert.equal(stomp.subscribeCalls, 2);
});

it('realtime client resolves relative API bases against the page origin', () => {
  const previousWindow = globalThis.window;
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: { location: { origin: 'https://mr.local' } },
  });

  try {
    const brokerUrls = ['/api', 'api', 'https://mr.local/api', undefined].map((baseUrl) => {
      let brokerURL: string | undefined;
      createRealtimeClient({
        baseUrl,
        clientFactory: (options) => {
          brokerURL = options.brokerURL;
          return new FakeStompClient();
        },
      });
      return brokerURL;
    });

    assert.deepEqual(brokerUrls, [
      'wss://mr.local/api/ws/platform',
      'wss://mr.local/api/ws/platform',
      'wss://mr.local/api/ws/platform',
      'wss://mr.local/ws/platform',
    ]);
  } finally {
    Object.defineProperty(globalThis, 'window', { configurable: true, value: previousWindow });
  }
});

it('realtime client uses ws for an HTTP page origin', () => {
  const previousWindow = globalThis.window;
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: { location: { origin: 'http://mr.local' } },
  });

  try {
    let brokerURL: string | undefined;
    createRealtimeClient({
      baseUrl: '/api',
      clientFactory: (options) => {
        brokerURL = options.brokerURL;
        return new FakeStompClient();
      },
    });
    assert.equal(brokerURL, 'ws://mr.local/api/ws/platform');
  } finally {
    Object.defineProperty(globalThis, 'window', { configurable: true, value: previousWindow });
  }
});

it('realtime client stops reconnecting on authentication errors', async () => {
  const stomp = new FakeStompClient();
  const states: string[] = [];
  const realtime = createRealtimeClient({
    clientFactory: (options) => {
      stomp.options = options;
      return stomp;
    },
    onStateChange: (state) => states.push(state),
  });

  await realtime.connect();
  stomp.error({ headers: { message: 'realtime authentication required' } });
  await Promise.resolve();
  await realtime.connect();

  assert.equal(realtime.state(), 'unauthorized');
  assert.deepEqual(states, ['connecting', 'unauthorized']);
  assert.equal(stomp.activateCalls, 1);
  assert.equal(stomp.connected, false);
});

it('realtime data change channel dispatches committed change sets', async () => {
  const stomp = new FakeStompClient();
  const realtime = createRealtimeClient({
    clientFactory: (options) => {
      stomp.options = options;
      return stomp;
    },
  });
  const dispatcher = createDataChangeDispatcher();
  const handled: string[] = [];
  dispatcher.subscribe((changeSet) => {
    handled.push(changeSet.changeSetId);
  });

  connectRealtimeDataChanges(realtime, dispatcher);
  await realtime.connect();
  stomp.connect();
  stomp.emit(
    '/user/queue/platform/data-changes',
    JSON.stringify({
      id: 'message-1',
      type: 'platform.data-change',
      occurredAt: '2026-07-15T10:00:00Z',
      payload: {
        changeSetId: 'change-set-1',
        changes: [{ type: 'record-created', moduleAlias: 'iam.organization', recordId: 'org-1' }],
      },
    }),
  );
  await Promise.resolve();

  assert.deepEqual(handled, ['change-set-1']);
});

it('realtime user notification channel handles security notifications', async () => {
  const stomp = new FakeStompClient();
  const realtime = createRealtimeClient({
    clientFactory: (options) => {
      stomp.options = options;
      return stomp;
    },
  });
  const handled: unknown[] = [];

  connectRealtimeUserNotifications(realtime, (notification) => {
    handled.push(notification);
  });
  await realtime.connect();
  stomp.connect();
  stomp.emit(
    '/user/queue/platform/notifications',
    JSON.stringify({
      id: 'message-1',
      type: 'platform.security-notification',
      occurredAt: '2026-07-15T10:00:00Z',
      payload: {
        code: 'platform.security.password-reset',
        message: '你的密码已被重置，请重新登录',
        logoutRequired: true,
      },
    }),
  );
  stomp.emit(
    '/user/queue/platform/notifications',
    JSON.stringify({
      id: 'message-2',
      type: 'platform.other',
      occurredAt: '2026-07-15T10:00:00Z',
      payload: { message: 'ignored' },
    }),
  );
  await Promise.resolve();

  assert.deepEqual(handled, [
    {
      code: 'platform.security.password-reset',
      message: '你的密码已被重置，请重新登录',
      logoutRequired: true,
    },
  ]);
});

it('realtime business event channel handles user private business events', async () => {
  const stomp = new FakeStompClient();
  const realtime = createRealtimeClient({
    clientFactory: (options) => {
      stomp.options = options;
      return stomp;
    },
  });
  const handled: unknown[] = [];

  connectRealtimeBusinessEvents(realtime, (event) => {
    handled.push(event);
  });
  await realtime.connect();
  stomp.connect();
  stomp.emit(
    '/user/queue/platform/business-events',
    JSON.stringify({
      id: 'message-1',
      type: 'platform.business-event',
      occurredAt: '2026-07-15T10:00:00Z',
      payload: {
        type: 'iam.user.session.collectionChanged',
        moduleAlias: 'iam.user',
        recordId: 'user-1',
        reason: 'LOGGED_IN',
        sensitivity: 'DIRTY_MARKER',
      },
    }),
  );
  stomp.emit(
    '/user/queue/platform/business-events',
    JSON.stringify({
      id: 'message-2',
      type: 'platform.other',
      occurredAt: '2026-07-15T10:00:00Z',
      payload: { type: 'ignored' },
    }),
  );
  await Promise.resolve();

  assert.deepEqual(handled, [
    {
      type: 'iam.user.session.collectionChanged',
      moduleAlias: 'iam.user',
      recordId: 'user-1',
      reason: 'LOGGED_IN',
      sensitivity: 'DIRTY_MARKER',
    },
  ]);
});

it('realtime channel factories build standard destinations', () => {
  assert.equal(realtimeDestinations.userDataChanges, '/user/queue/platform/data-changes');
  assert.deepEqual(userNotificationChannel, {
    destination: '/user/queue/platform/notifications',
    type: 'platform.security-notification',
  });
  assert.deepEqual(userBusinessEventChannel, {
    destination: '/user/queue/platform/business-events',
    type: 'platform.business-event',
  });
  assert.equal(userImMessageChannel.destination, '/user/queue/platform/im/messages');
  assert.equal(sessionActivityCommand.destination, '/app/platform/session/activity');
  assert.equal(imMessageSendCommand.destination, '/app/platform/im/messages/send');
  assert.deepEqual(tenantPublicDataChangeChannel('tenant-a'), {
    destination: '/topic/platform/tenants/tenant-a/public/data-changes',
    type: 'platform.data-change',
  });
  assert.deepEqual(tenantPublicNotificationChannel('tenant-a'), {
    destination: '/topic/platform/tenants/tenant-a/public/notifications',
  });
  assert.deepEqual(organizationPublicDataChangeChannel('org-1'), {
    destination: '/topic/platform/organizations/org-1/public/data-changes',
    type: 'platform.data-change',
  });
  assert.deepEqual(organizationPublicNotificationChannel('org-1'), {
    destination: '/topic/platform/organizations/org-1/public/notifications',
  });
  assert.deepEqual(moduleDataChangeChannel('iam.employee'), {
    destination: '/topic/platform/modules/iam.employee/data-changes',
    type: 'platform.data-change',
  });
  assert.deepEqual(recordDataChangeChannel('iam.employee', 'employee-1'), {
    destination: '/topic/platform/modules/iam.employee/records/employee-1/data-changes',
    type: 'platform.data-change',
  });
  assert.deepEqual(resourceDataChangeChannel('iam.employee', 'children'), {
    destination: '/topic/platform/modules/iam.employee/resources/children/data-changes',
    type: 'platform.data-change',
  });
  assert.deepEqual(resourceRecordDataChangeChannel('iam.employee', 'children', 'employee-1'), {
    destination: '/topic/platform/modules/iam.employee/resources/children/records/employee-1/data-changes',
    type: 'platform.data-change',
  });
  assert.deepEqual(contextDataChangeChannel('workflow', 'task-1'), {
    destination: '/topic/platform/contexts/workflow/task-1/data-changes',
    type: 'platform.data-change',
  });
  assert.deepEqual(imConversationMessageChannel('conversation-1'), {
    destination: '/topic/platform/im/conversations/conversation-1/messages',
  });
});

it('realtime channel factories encode destination path segments', () => {
  assert.equal(
    recordDataChangeChannel('order/form', 'record 1').destination,
    '/topic/platform/modules/order%2Fform/records/record%201/data-changes',
  );
  assert.throws(() => moduleDataChangeChannel(' '), /Realtime destination path segment must not be blank/);
});

it('static module client normalizes backend action envelopes', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    if (request.url.endsWith('/insert')) {
      return Response.json({
        data: { id: 'org-1', title: '总部' },
        message: { code: 'platform.crud.created', text: '新增成功', type: 'SUCCESS' },
        changeSetId: 'change-set-1',
        changes: [{ type: 'record-created', moduleAlias: 'iam.organization', recordId: 'org-1' }],
      });
    }
    return Response.json({
      data: 1,
      message: { code: 'platform.crud.deleted', text: '删除成功', type: 'SUCCESS' },
      changeSetId: 'change-set-2',
      changes: [{ type: 'record-deleted', moduleAlias: 'iam.organization', recordId: 'org-1' }],
    });
  };

  try {
    const client = createModuleCrudClient(createHttpClient({ baseUrl: 'http://api.local' }), {
      moduleAlias: 'iam.organization',
    });

    assert.deepEqual(await client.insert({ title: '总部' }), {
      record: { id: 'org-1', title: '总部' },
      message: { code: 'platform.crud.created', text: '新增成功', type: 'SUCCESS' },
      changeSetId: 'change-set-1',
      changes: [{ type: 'record-created', moduleAlias: 'iam.organization', recordId: 'org-1' }],
    });
    await client.update('org-1', { id: 'org-1', title: '新总部' });
    assert.deepEqual(await client.delete('org-1', { version: 3 }), {
      data: 1,
      message: { code: 'platform.crud.deleted', text: '删除成功', type: 'SUCCESS' },
      changeSetId: 'change-set-2',
      changes: [{ type: 'record-deleted', moduleAlias: 'iam.organization', recordId: 'org-1' }],
    });
    assert.deepEqual(await requests[1].json(), { id: 'org-1', title: '新总部' });
    assert.equal(await requests[2].text(), '{"version":3}');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('module client unwraps dynamic record transport into the shared record contract', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    const record = {
      id: 'exam-1',
      version: 2,
      values: { title: '期中测评', examDate: '2026-04-18' },
      children: {
        participants: [
          {
            id: 'participant-1',
            version: 0,
            values: { studentNo: 'S2026001', score: '92.50' },
            children: {},
          },
        ],
      },
    };
    if (request.url.endsWith('/query')) {
      return Response.json({ records: [record], total: 1, pageNum: 1, pageSize: 20, pages: 1 });
    }
    if (request.url.includes('/view/')) return Response.json(record);
    return Response.json({ data: record, changeSetId: 'change-set-1' });
  };

  try {
    const client = createModuleCrudClient<Record<string, unknown>>(
      createHttpClient({ baseUrl: 'http://api.local' }),
      {
        moduleAlias: 'education.exam',
      },
    );

    const expected = {
      id: 'exam-1',
      version: 2,
      title: '期中测评',
      examDate: '2026-04-18',
      participants: [{ id: 'participant-1', version: 0, studentNo: 'S2026001', score: '92.50' }],
    };
    assert.deepEqual((await client.query()).records, [expected]);
    assert.deepEqual(await client.view('exam-1'), expected);
    assert.deepEqual(await client.insert({ title: '期中测评' }), {
      record: expected,
      changeSetId: 'change-set-1',
      message: undefined,
      changes: undefined,
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('static module tree client maps standard CRUD and tree endpoints by module alias', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    const url = new URL(request.url);
    if (url.pathname.endsWith('/tree') && url.searchParams.get('flat') === 'true') {
      return Response.json({ records: [] });
    }
    if (url.pathname.endsWith('/query/schema')) {
      return Response.json({
        scopeName: 'iam.organization',
        quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
        fields: [],
        externalCriteria: [],
        defaultSorts: [],
      });
    }
    if (request.url.endsWith('/insert')) {
      return Response.json({
        data: { id: 'org-1', title: '总部' },
        message: { code: 'platform.crud.created', text: '新增成功', type: 'SUCCESS' },
        changeSetId: 'change-set-1',
        changes: [{ type: 'record-created', moduleAlias: 'iam.organization', recordId: 'org-1' }],
      });
    }
    return Response.json(1);
  };

  try {
    const client = createModuleTreeClient(createHttpClient({ baseUrl: 'http://api.local' }), {
      moduleAlias: 'iam.organization',
    });

    await client.treeFlat();
    await client.tree({ externalQueryValues: { tenantId: 'tenant-a' } });
    await client.querySchema();
    await client.querySchema({ uiConfigId: 'org-list-v1' });
    const insertResult = await client.insert({ title: '总部' });
    await client.sort('org-1', { parentId: 'root' });

    assert.equal(requests[0].url, 'http://api.local/iam.organization/tree?flat=true');
    assert.equal(requests[0].method, 'GET');
    assert.equal(requests[1].url, 'http://api.local/iam.organization/tree/query');
    assert.equal(requests[1].method, 'POST');
    assert.deepEqual(await requests[1].json(), { externalQueryValues: { tenantId: 'tenant-a' } });
    assert.equal(requests[2].url, 'http://api.local/iam.organization/query/schema');
    assert.equal(requests[2].method, 'GET');
    assert.equal(requests[3].url, 'http://api.local/iam.organization/query/schema?uiConfigId=org-list-v1');
    assert.equal(requests[3].method, 'GET');
    assert.equal(requests[4].url, 'http://api.local/iam.organization/insert');
    assert.equal(requests[4].method, 'POST');
    assert.deepEqual(await requests[4].json(), { title: '总部' });
    assert.deepEqual(insertResult, {
      record: { id: 'org-1', title: '总部' },
      message: { code: 'platform.crud.created', text: '新增成功', type: 'SUCCESS' },
      changeSetId: 'change-set-1',
      changes: [{ type: 'record-created', moduleAlias: 'iam.organization', recordId: 'org-1' }],
    });
    assert.equal(requests[5].url, 'http://api.local/iam.organization/sort/org-1');
    assert.deepEqual(await requests[5].json(), { parentId: 'root' });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('retains static module client factories for published consumer compatibility', () => {
  assert.equal(createStaticModuleCrudClient, createModuleCrudClient);
  assert.equal(createStaticModuleTreeClient, createModuleTreeClient);
});

it('module context creates standard CRUD capabilities from configured http factory', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    if (request.url.endsWith('/context')) {
      return Response.json({
        ...runtimeContext(),
        moduleAlias: 'iam.user',
        actions: [
          ...runtimeContext().actions,
          {
            actionCode: 'resetPassword',
            permissionActionCode: 'resetPassword',
            title: 'Reset Password',
            authorized: true,
          },
        ],
      });
    }
    return Response.json({ records: [] });
  };

  try {
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const context = createModuleContext({ moduleAlias: 'iam.organization' });

    await context.runtime.ready;
    await context.abilities.crud().query({ quickSearch: '总部' });

    assert.equal(context.moduleAlias, 'iam.organization');
    assert.equal(requests[0].url, 'http://api.local/platform.module/iam.organization/context');
    assert.equal(requests[1].url, 'http://api.local/iam.organization/query');
    assert.equal(requests[1].method, 'POST');
    assert.deepEqual(await requests[1].json(), { quickSearch: '总部' });
    assert.equal(context.runtime.can('update'), true);
    assert.equal(context.runtime.action('update')?.available, true);
    assert.equal(context.runtime.action('update')?.title, 'Update');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('navigator reference contexts attach their immutable host level to list and tree requests', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    if (request.url.endsWith('/reference-context')) {
      return Response.json({
        ...runtimeContext(),
        navigatorSourceCapabilities: ['REFERENCE_QUERY', 'REFERENCE_TREE'],
      });
    }
    return Response.json({ records: [] });
  };

  try {
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const context = createModuleContext({
      moduleAlias: 'mr.project',
      runtimeAccess: 'REFERENCE',
      navigatorReference: { hostModuleAlias: 'mr.device', targetLevelKey: 'project' },
    });

    await context.runtime.ready;
    await context.crud.query({ externalQueryValues: { tenantId: 'tenant-a' } });

    assert.equal(requests[1].url, 'http://api.local/mr.project/navigator/reference/query');
    assert.deepEqual(await requests[1].json(), {
      externalQueryValues: { tenantId: 'tenant-a' },
      navigatorHostModuleAlias: 'mr.device',
      navigatorTargetLevelKey: 'project',
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('module runtime authorization updates Vue computed state after context loads', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json(runtimeContext());

  try {
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const context = createModuleContext({ moduleAlias: 'iam.organization' });
    const canCreate = computed(() => context.can('create') === true);

    assert.equal(canCreate.value, false);

    await context.runtime.ready;
    await nextTick();

    assert.equal(canCreate.value, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('module context resolves record action availability by record id', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    if (request.url.endsWith('/context')) {
      return Response.json({
        ...runtimeContext(),
        actions: [
          ...runtimeContext().actions,
          {
            actionCode: 'resetPassword',
            permissionActionCode: 'resetPassword',
            title: 'Reset Password',
            authorized: true,
          },
        ],
      });
    }
    return Response.json({
      recordId: 'platform.user.super_admin',
      actions: [
        { actionCode: 'update', available: true },
        {
          actionCode: 'resetPassword',
          available: false,
          reason: "cannot administrate current user's password",
        },
      ],
    });
  };

  try {
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const context = createModuleContext({ moduleAlias: 'iam.user' });

    await context.runtime.ready;

    assert.equal(context.can('update'), true);
    assert.equal(context.action('update')?.available, true);
    assert.equal(context.can('resetPassword', 'platform.user.super_admin'), undefined);
    assert.equal(context.action('resetPassword', 'platform.user.super_admin'), undefined);

    const availability = await context.recordActions('platform.user.super_admin');

    assert.equal(requests[1].url, 'http://api.local/iam.user/actions/platform.user.super_admin');
    assert.equal(availability.recordId, 'platform.user.super_admin');
    assert.equal(context.can('update', 'platform.user.super_admin'), true);
    assert.equal(context.action('update', 'platform.user.super_admin')?.available, true);
    assert.equal(context.can('resetPassword', 'platform.user.super_admin'), false);
    assert.deepEqual(
      {
        available: context.action('resetPassword', 'platform.user.super_admin')?.available,
        reason: context.action('resetPassword', 'platform.user.super_admin')?.reason,
      },
      {
        available: false,
        reason: "cannot administrate current user's password",
      },
    );
    assert.equal(
      context.recordActionsSnapshot('platform.user.super_admin')?.actions[1]?.reason,
      "cannot administrate current user's password",
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('module context ignores record action decisions without runtime definition', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    if (request.url.endsWith('/context')) {
      return Response.json(runtimeContext());
    }
    return Response.json({
      recordId: 'org-1',
      actions: [{ actionCode: 'ghost', available: true }],
    });
  };

  try {
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const context = createModuleContext({ moduleAlias: 'iam.organization' });

    await context.runtime.ready;
    await context.recordActions('org-1');

    assert.equal(context.can('ghost', 'org-1'), undefined);
    assert.equal(context.action('ghost', 'org-1'), undefined);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('module context batches, caches, and invalidates record action availability', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    if (request.url.endsWith('/context')) {
      return Response.json(runtimeContext());
    }
    return Response.json([
      { recordId: 'org-1', actions: [{ actionCode: 'update', available: false, reason: '受保护记录' }] },
      { recordId: 'org-2', actions: [{ actionCode: 'update', available: true }] },
    ]);
  };

  try {
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const context = createModuleContext({ moduleAlias: 'iam.organization' });
    await context.runtime.ready;

    await context.recordActionsBatch?.(['org-1', 'org-2', 'org-1']);
    const batchRequest = requests.find((request) =>
      request.url.endsWith('/iam.organization/actions/availability'),
    );
    assert.ok(batchRequest);
    assert.deepEqual(await batchRequest.json(), { recordIds: ['org-1', 'org-2'] });
    assert.equal(context.action('update', 'org-1')?.reason, '受保护记录');

    await context.recordActionsBatch?.(['org-1', 'org-2']);
    assert.equal(requests.filter((request) => request.url.endsWith('/actions/availability')).length, 1);
    context.invalidateRecordActions?.(['org-1']);
    assert.equal(context.recordActionsSnapshot('org-1'), undefined);
    assert.notEqual(context.recordActionsSnapshot('org-2'), undefined);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('module context abilities compose tree and enable capabilities', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    if (request.url.endsWith('/context')) {
      return Response.json(runtimeContext());
    }
    return Response.json({ records: [] });
  };

  try {
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const context = createModuleContext<{ id?: string }>({ moduleAlias: 'iam.organization' });
    assert.equal(context.abilities.tryTree(), undefined);
    assert.throws(() => context.abilities.tree(), /Module runtime context is not ready/);

    await context.runtime.ready;
    const tree = context.abilities.tree();
    const enable = context.abilities.enable();

    await context.abilities.crud().query({ quickSearch: '总部' });
    await tree.tree();
    await enable.disable('org-1', { version: 4 });

    assert.equal(context.moduleAlias, 'iam.organization');
    assert.equal(requests[0].url, 'http://api.local/platform.module/iam.organization/context');
    assert.equal(requests[1].url, 'http://api.local/iam.organization/query');
    assert.equal(requests[2].url, 'http://api.local/iam.organization/tree');
    assert.equal(requests[3].url, 'http://api.local/iam.organization/disable/org-1');
    assert.equal(await requests[3].text(), '{"version":4}');
    assert.equal(context.abilities.hasTree(), true);
    assert.equal(context.abilities.has('tree'), true);
    assert.equal(context.abilities.has('recycleBin'), true);
    assert.equal(hasRecycleBinAbility(context), true);
    assert.equal(canQueryRecycleBin(context), true);
    assert.equal(context.abilities.tryTree(), tree);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('recycle-bin access requires both module ability and query permission', () => {
  const context = {
    abilities: { has: () => false },
    can: () => true,
  } as unknown as ReturnType<typeof createModuleContext>;

  assert.equal(hasRecycleBinAbility(context), false);
  assert.equal(canQueryRecycleBin(context), false);

  context.abilities.has = () => true;
  context.can = () => false;

  assert.equal(hasRecycleBinAbility(context), true);
  assert.equal(canQueryRecycleBin(context), false);
});

it('module tree context remains compatible with explicit tree opt-in', async () => {
  const requests: Request[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    if (request.url.endsWith('/context')) {
      return Response.json(runtimeContext());
    }
    return Response.json({ records: [] });
  };

  try {
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const context = createModuleTreeContext({ moduleAlias: 'iam.organization' });

    await context.tree.tree();
    await context.runtime.ready;

    assert.equal(requests[0].url, 'http://api.local/platform.module/iam.organization/context');
    assert.equal(requests[1].url, 'http://api.local/iam.organization/tree');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('module runtime context records background load errors and retries explicit load', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    if (calls === 1) {
      return Response.json(
        {
          code: platformErrorCodes.accessDenied,
          status: 403,
          message: '权限不足',
        },
        { status: 403 },
      );
    }
    return Response.json(runtimeContext());
  };

  try {
    const context = createModuleContext({ moduleAlias: 'iam.organization', http: createHttpClient() });

    await expectRejected(() => context.runtime.ready);

    assert.equal(context.runtime.error()?.code, platformErrorCodes.accessDenied);

    const loaded = await context.runtime.load();

    assert.equal(loaded.moduleAlias, 'iam.organization');
    assert.equal(context.runtime.error(), undefined);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client maps unified backend error envelope to AppError facts', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    Response.json(
      {
        traceId: 'trace-body',
        code: 'DYNAMIC_FIELD_REQUIRED',
        status: 422,
        message: '客户名称不能为空',
        scope: { moduleAlias: 'crm.customer' },
        targets: [{ kind: 'field', fieldName: 'customerName' }],
        details: { rule: 'required' },
      },
      { status: 422 },
    );

  try {
    const http = createHttpClient({ baseUrl: 'http://api.local' });

    await expectRejected(
      () => http.request({ path: '/dynamic/crm.customer/records' }),
      (error) => {
        assert.equal(error instanceof AppError, true);
        const appError = error as AppError;
        assert.equal(appError.message, '客户名称不能为空');
        assert.equal(appError.code, 'DYNAMIC_FIELD_REQUIRED');
        assert.equal(appError.status, 422);
        assert.equal(appError.traceId, 'trace-body');
        assert.deepEqual(appError.scope, { moduleAlias: 'crm.customer' });
        assert.deepEqual(appError.targets, [{ kind: 'field', fieldName: 'customerName' }]);
        assert.deepEqual(appError.details, { rule: 'required' });
        return true;
      },
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client falls back to response trace header for AppError traceId', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    Response.json(
      {
        code: platformErrorCodes.configMissing,
        status: 409,
        message: '菜单方案未配置',
      },
      { status: 409, headers: { 'X-MuYun-Trace-Id': 'trace-header' } },
    );

  try {
    const http = createHttpClient({ baseUrl: 'http://api.local' });

    await expectRejected(
      () => http.request({ path: '/platform.menu/mine' }),
      (error) => {
        assert.equal(error instanceof AppError, true);
        assert.equal((error as AppError).traceId, 'trace-header');
        return true;
      },
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

it('http client wraps invalid json error response as AppError', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response('{', {
      status: 500,
      headers: { 'Content-Type': 'application/json', 'X-MuYun-Trace-Id': 'trace-invalid-json' },
    });

  try {
    const http = createHttpClient({ baseUrl: 'http://api.local' });

    await expectRejected(
      () => http.request({ path: '/platform.broken' }),
      (error) => {
        assert.equal(error instanceof AppError, true);
        const appError = error as AppError;
        assert.equal(appError.code, platformErrorCodes.httpError);
        assert.equal(appError.status, 500);
        assert.equal(appError.traceId, 'trace-invalid-json');
        assert.match(String(appError.details?.cause), /JSON/);
        return true;
      },
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

function runtimeContext() {
  return {
    moduleAlias: 'iam.organization',
    title: '组织管理',
    moduleKind: 'STATIC',
    entryType: 'route',
    entryRoute: '/iam/organizations',
    mainEntityAlias: 'organization',
    capabilities: ['CRUD', 'SOFT_DELETE', 'LIFECYCLE', 'CACHE', 'TREE', 'SORT', 'ENABLE', 'RECYCLE_BIN'],
    abilities: ['crud', 'softDelete', 'lifecycle', 'cache', 'tree', 'sort', 'enable', 'recycleBin'],
    actions: [
      { actionCode: 'query', permissionActionCode: 'view', title: 'Query', authorized: true },
      { actionCode: 'create', permissionActionCode: 'create', title: 'Create', authorized: true },
      { actionCode: 'update', permissionActionCode: 'update', title: 'Update', authorized: true },
      { actionCode: 'tree', permissionActionCode: 'view', title: 'Tree', authorized: true },
      { actionCode: 'disable', permissionActionCode: 'enable', title: 'Disable', authorized: true },
      { actionCode: 'recycleBinQuery', title: 'Recycle bin', authorized: true },
    ],
  };
}

class FakeStompClient implements StompClientAdapter {
  connected = false;
  options?: StompClientFactoryOptions;
  activateCalls = 0;
  subscribeCalls = 0;
  private readonly subscriptions = new Map<string, Set<(message: IMessage) => void>>();

  activate() {
    this.activateCalls += 1;
    // The test controls the exact connect timing through connect().
  }

  async deactivate() {
    this.connected = false;
    this.options?.onDisconnect();
  }

  connect() {
    this.connected = true;
    this.options?.onConnect();
  }

  subscribe(destination: string, handler: (message: IMessage) => void): StompSubscriptionLike {
    this.subscribeCalls += 1;
    const handlers = this.subscriptions.get(destination) ?? new Set();
    handlers.add(handler);
    this.subscriptions.set(destination, handlers);
    return {
      unsubscribe: () => {
        handlers.delete(handler);
      },
    };
  }

  publish() {
    // Publish behavior is not needed by these tests.
  }

  emit(destination: string, body: string) {
    for (const handler of this.subscriptions.get(destination) ?? []) {
      handler({ body } as IMessage);
    }
  }

  error(frame?: { headers?: Record<string, string>; body?: string }) {
    this.options?.onStompError(frame);
  }
}

it('normalizeError keeps AppError and wraps unknown errors', () => {
  const appError = new AppError('conflict', { code: platformErrorCodes.conflictVersion, status: 409 });

  assert.equal(normalizeError(appError), appError);
  assert.deepEqual(
    normalizeError(new Error('boom')),
    new AppError('boom', { code: platformErrorCodes.appError }),
  );
  assert.deepEqual(normalizeError('boom').details, { cause: 'boom' });
});

it('resolveGlobalErrorPresentation maps common failures to fixed global slots', () => {
  assert.equal(
    resolveGlobalErrorPresentation(new AppError('login required', { status: 401 }), {
      phase: 'action',
      surface: 'workbench',
    }).slot,
    'redirect-login',
  );
  assert.equal(
    resolveGlobalErrorPresentation(
      new AppError('bad credentials', { code: platformErrorCodes.loginBadCredentials, status: 401 }),
      {
        phase: 'action',
        surface: 'form',
      },
    ).slot,
    'global-toast',
  );
  assert.equal(
    resolveGlobalErrorPresentation(new AppError('forbidden', { status: 403 }), {
      phase: 'page-load',
      surface: 'workbench',
    }).slot,
    'page-error',
  );
  assert.equal(
    resolveGlobalErrorPresentation(new AppError('conflict', { status: 409 }), {
      phase: 'action',
      surface: 'form',
    }).slot,
    'global-modal',
  );
  assert.equal(
    resolveGlobalErrorPresentation(new AppError('failed', { status: 500 }), {
      phase: 'page-load',
      surface: 'workbench',
    }).slot,
    'page-error',
  );
  assert.equal(
    resolveGlobalErrorPresentation(new AppError('failed', { status: 500 }), {
      phase: 'background',
      surface: 'unknown',
    }).slot,
    'silent',
  );
  assert.equal(
    resolveGlobalErrorPresentation(new AppError('bad request', { status: 400 })).slot,
    'global-toast',
  );
});
