import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import type { DurationSecondsInputProps } from '../../../src/consumer';

it('consumes the public duration component from an independently built package', async () => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run through npm run verify:consumer.');
  const sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
  const props: DurationSecondsInputProps = { value: 259200, ariaLabel: 'Cache validity' };
  const wrapper = mount(sdk.DurationSecondsInput, { props });
  try {
    expect(wrapper.get('[role="group"]').attributes('aria-label')).toBe('Cache validity');
    const input = wrapper.get('input[type="number"]');
    expect((input.element as HTMLInputElement).value).toBe('3');
    await input.setValue('2');
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual([172800]);
    await input.setValue('-1');
    expect(wrapper.get('[role="group"]').attributes('aria-invalid')).toBe('true');
    expect(wrapper.emitted('update:value')).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});
