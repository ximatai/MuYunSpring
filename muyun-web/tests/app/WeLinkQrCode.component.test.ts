import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import WeLinkQrCode from '@/app/WeLinkQrCode.vue';
import type { ExternalLoginStart } from '@muyun/web-contracts';
import { WELINK_ORIGIN, WELINK_SCRIPT } from '@/app/weLinkQrCode';

const navigate = vi.hoisted(() => vi.fn());
vi.mock('@/app/externalLoginReturn', () => ({ navigateToExternalAuthorization: navigate }));

const start = (): ExternalLoginStart => ({
  transactionId: 'tx',
  state: 'state & protected',
  browserProof: 'browser-only',
  authorizationUrl: `${WELINK_ORIGIN}/sso/oauth2/sns_authorize`,
  expiresAt: new Date(Date.now() + 180000).toISOString(),
  qrCode: {
    providerType: 'welink',
    scriptUrl: WELINK_SCRIPT,
    authorizationUrl: `${WELINK_ORIGIN}/sso/oauth2/sns_authorize`,
    clientId: 'client & id',
    redirectUri: 'https://workbench.test/app/',
    state: 'state & protected',
    scope: 'snsapi_login',
    messageOrigin: WELINK_ORIGIN,
  },
});

describe('WeLink QR authorization', () => {
  beforeEach(() => {
    navigate.mockReset();
    window.wlQrcodeLogin = vi.fn((options) => {
      const iframe = document.createElement('iframe');
      iframe.src = WELINK_ORIGIN;
      document.getElementById(options.id)!.append(iframe);
    });
  });
  afterEach(() => {
    delete window.wlQrcodeLogin;
    vi.useRealTimers();
  });

  it('loads the official component and accepts only a string from its own iframe and official origin', async () => {
    const wrapper = mount(WeLinkQrCode, { props: { start: start() }, attachTo: document.body });
    await flushPromises();
    const frame = wrapper.get('iframe').element as HTMLIFrameElement;
    const dispatch = (data: unknown, origin: string, source: MessageEventSource | null) => {
      window.dispatchEvent(new MessageEvent('message', { data, origin, source }));
    };
    dispatch('stolen', 'https://attacker.test', frame.contentWindow);
    dispatch('stolen', WELINK_ORIGIN, window);
    dispatch({ code: 'wrong-shape' }, WELINK_ORIGIN, frame.contentWindow);
    dispatch('', WELINK_ORIGIN, frame.contentWindow);
    expect(navigate).not.toHaveBeenCalled();
    dispatch('login+code&value', WELINK_ORIGIN, frame.contentWindow);
    expect(navigate).toHaveBeenCalledTimes(1);
    const url = new URL(navigate.mock.calls[0]![0]);
    expect(url.origin).toBe(WELINK_ORIGIN);
    expect(Object.fromEntries(url.searchParams)).toEqual({
      client_id: 'client & id',
      redirect_uri: 'https://workbench.test/app/',
      response_type: 'code',
      scope: 'snsapi_login',
      state: 'state & protected',
      code: 'login+code&value',
    });
    expect(url.search).not.toContain('browser-only');
    dispatch('second', WELINK_ORIGIN, frame.contentWindow);
    expect(navigate).toHaveBeenCalledTimes(1);
    wrapper.unmount();
    dispatch('late', WELINK_ORIGIN, frame.contentWindow);
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('shows a useful fallback for script failure and can close', async () => {
    delete window.wlQrcodeLogin;
    const wrapper = mount(WeLinkQrCode, { props: { start: start() }, attachTo: document.body });
    const script = document.querySelector(`script[src="${WELINK_SCRIPT}"]`)!;
    script.dispatchEvent(new Event('error'));
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain('无法加载');
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('close')).toHaveLength(1);
    wrapper.unmount();
  });

  it('rejects unrecognized script addresses before loading anything', async () => {
    const value = start();
    value.qrCode!.scriptUrl = 'https://attacker.test/script.js';
    const wrapper = mount(WeLinkQrCode, { props: { start: value } });
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    expect(window.wlQrcodeLogin).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('expires with the server transaction and ignores late messages', async () => {
    vi.useFakeTimers();
    const value = start();
    value.expiresAt = new Date(Date.now() + 1000).toISOString();
    const wrapper = mount(WeLinkQrCode, { props: { start: value }, attachTo: document.body });
    await flushPromises();
    const source = (wrapper.get('iframe').element as HTMLIFrameElement).contentWindow;
    await vi.advanceTimersByTimeAsync(1000);
    window.dispatchEvent(new MessageEvent('message', { data: 'late', origin: WELINK_ORIGIN, source }));
    expect(navigate).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('超时');
    wrapper.unmount();
  });
});
