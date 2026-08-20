import { flushPromises, shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import TenantApplicationsDetailSection from '@/platform-admin-runtime/tenant/TenantApplicationsDetailSection.vue';
import TenantApplicationConfigurationDrawer from '@/platform-admin-runtime/tenant/TenantApplicationConfigurationDrawer.vue';

describe('tenant detail extensions', () => {
  it('reloads the provisioned-application section when the standard detail extension refreshes', async () => {
    const request = vi.fn().mockResolvedValue({ records: [] });
    const context = {
      module: { http: { request } },
      record: { id: 'tenant-a' },
      refreshList: vi.fn(),
      refreshKey: 0,
      reload: vi.fn(),
    };
    const wrapper = shallowMount(TenantApplicationsDetailSection, { props: { context: context as never } });

    await flushPromises();
    expect(request).toHaveBeenCalledTimes(1);
    await wrapper.setProps({ context: { ...context, refreshKey: 1 } as never });
    await flushPromises();
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('keeps drawer title actions synchronized while saving and refreshes detail extensions after success', async () => {
    let resolveConfigure: (() => void) | undefined;
    const request = vi
      .fn()
      .mockResolvedValueOnce({ records: [{ alias: 'iam', enabled: true }] })
      .mockResolvedValueOnce({ records: [{ applicationAlias: 'iam' }] })
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            resolveConfigure = resolve;
          }),
      );
    const titleActions: Array<{ key: string; disabled?: boolean; loading?: boolean; run: () => unknown }> =
      [];
    const setCloseBlocked = vi.fn();
    const context = {
      module: { http: { request } },
      record: { id: 'tenant-a' },
      refreshList: vi.fn(),
      refreshDetailExtensions: vi.fn(),
      setCloseBlocked,
      close: vi.fn(),
      reload: vi.fn(),
      setTitleActions(actions: typeof titleActions) {
        titleActions.splice(0, titleActions.length, ...actions);
      },
    };
    shallowMount(TenantApplicationConfigurationDrawer, { props: { context: context as never } });
    await flushPromises();

    void titleActions.find((action) => action.key === 'confirm')!.run();
    await Promise.resolve();
    expect(titleActions.find((action) => action.key === 'confirm')).toMatchObject({
      disabled: true,
      loading: true,
    });
    expect(titleActions.find((action) => action.key === 'cancel')?.disabled).toBe(true);
    expect(setCloseBlocked).toHaveBeenLastCalledWith(true);

    resolveConfigure?.();
    await flushPromises();
    expect(context.refreshDetailExtensions).toHaveBeenCalledOnce();
    expect(context.refreshList).toHaveBeenCalledOnce();
    expect(context.close).toHaveBeenCalledOnce();
    expect(setCloseBlocked).toHaveBeenLastCalledWith(false);
  });
});
