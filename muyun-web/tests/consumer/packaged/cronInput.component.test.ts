import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import type { CronExpressionInputProps } from '../../../src/consumer';

it('consumes the public Cron component and validator without a private module import', async () => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run through npm run verify:consumer.');
  const sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
  const request = vi.fn().mockResolvedValue({ valid: true });
  const props: CronExpressionInputProps = {
    value: '0 0 9 ? * MON#2',
    validator: (expression) => sdk.validateCronExpression({ request }, expression),
  };
  const wrapper = mount(sdk.CronExpressionInput, { props });
  try {
    await flushPromises();
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/platform.schedule/validate-cron',
      body: { expression: props.value },
    });
    expect(wrapper.emitted('update:value')).toBeUndefined();
    request.mockResolvedValue({ valid: false, reason: 'MINUTE_PRECISION_REQUIRED' });
    await wrapper.get('input').setValue('*/2 * * * * *');
    await wrapper.get('input').trigger('blur');
    await flushPromises();
    expect(wrapper.get('[role="group"]').attributes('aria-invalid')).toBe('true');
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({
      valid: false,
      message: expect.any(String),
    });
  } finally {
    wrapper.unmount();
  }
});
