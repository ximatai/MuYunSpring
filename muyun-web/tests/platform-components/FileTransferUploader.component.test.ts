import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import FileTransferUploader from '@/platform-components/FileTransferUploader.vue';

describe('FileTransferUploader', () => {
  it('does not expose a native file chooser while disabled', async () => {
    const wrapper = mount(FileTransferUploader, {
      props: { disabled: true },
    });
    const input = wrapper.find('input[type="file"]');
    const click = vi.spyOn(input.element, 'click');

    await wrapper.find('.file-transfer-uploader__drop-zone').trigger('click');

    expect(input.attributes('disabled')).toBeDefined();
    expect(click).not.toHaveBeenCalled();
  });

  it('keeps the compact button presentation disabled', async () => {
    const wrapper = mount(FileTransferUploader, {
      props: { disabled: true, presentation: 'button' },
    });
    const input = wrapper.find('input[type="file"]');
    const click = vi.spyOn(input.element, 'click');

    await wrapper.find('.file-transfer-uploader__choose-button').trigger('click');

    expect(wrapper.find('.file-transfer-uploader__choose-button').attributes('disabled')).toBeDefined();
    expect(click).not.toHaveBeenCalled();
  });
});
