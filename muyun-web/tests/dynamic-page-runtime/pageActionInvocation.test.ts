import { expect, it, vi } from 'vitest';
import { invokePageAction } from '@/dynamic-page-runtime/pageActionInvocation';

it('uses the compiled method and nonstandard route and encodes record identity', async () => {
  const request = vi.fn().mockResolvedValue({ message: 'done' });
  await invokePageAction(
    { request },
    {
      method: 'GET',
      path: '/iam.user/{recordId}/sessions',
      input: 'NONE',
    },
    { recordId: 'user/a ?' },
  );
  expect(request).toHaveBeenCalledExactlyOnceWith({
    method: 'GET',
    path: '/iam.user/user%2Fa%20%3F/sessions',
  });
});

it('sends a form draft only for the compiled form input contract', async () => {
  const request = vi.fn().mockResolvedValue({ recordPatch: {} });
  const record = { id: 'one', version: 2, lines: [{ quantity: 3 }] };
  await invokePageAction(
    { request },
    {
      method: 'POST',
      path: '/demo.order/form-actions/calculate',
      input: 'FORM_RECORD',
    },
    { record },
  );
  expect(request).toHaveBeenCalledExactlyOnceWith({
    method: 'POST',
    path: '/demo.order/form-actions/calculate',
    body: { record },
  });
});

it('refuses missing execution facts and missing context without making an HTTP request', async () => {
  const request = vi.fn();
  await expect(invokePageAction({ request }, undefined)).rejects.toThrow('执行入口');
  await expect(
    invokePageAction(
      { request },
      {
        method: 'POST',
        path: '/demo.order/{recordId}/approve',
        input: 'NONE',
      },
    ),
  ).rejects.toThrow('当前记录');
  await expect(
    invokePageAction(
      { request },
      {
        method: 'POST',
        path: '/demo.order/form-actions/calculate',
        input: 'FORM_RECORD',
      },
    ),
  ).rejects.toThrow('当前草稿');
  await expect(
    invokePageAction(
      { request },
      {
        method: 'POST',
        path: '/demo.order/{parentId}/approve',
        input: 'NONE',
      },
    ),
  ).rejects.toThrow('未绑定参数');
  expect(request).not.toHaveBeenCalled();
});
