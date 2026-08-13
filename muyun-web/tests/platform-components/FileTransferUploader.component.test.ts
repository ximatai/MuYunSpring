import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import FileTransferUploader from '@/platform-components/FileTransferUploader.vue';

describe('FileTransferUploader', () => {
  it('keeps a field boundary below the dropzone presentation', () => {
    const dropzone = mount(FileTransferUploader);
    const button = mount(FileTransferUploader, { props: { presentation: 'button' } });

    expect(dropzone.classes()).toContain('file-transfer-uploader--dropzone');
    expect(button.classes()).not.toContain('file-transfer-uploader--dropzone');
  });

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

  it('rejects a business-invalid selected file before upload', async () => {
    const uploadFile = vi.fn();
    const wrapper = mount(FileTransferUploader, {
      props: {
        uploadFile,
        uploadValidation: () => '仅允许正方形 Logo',
      },
    });
    const file = new File(['image'], 'horizontal.png', { type: 'image/png' });
    const input = wrapper.get('input[type="file"]');

    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] });
    await input.trigger('change');
    await vi.waitFor(() => expect(wrapper.text()).toContain('仅允许正方形 Logo'));

    expect(uploadFile).not.toHaveBeenCalled();
  });
});
