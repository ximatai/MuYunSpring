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

  it('uses the shared square hit area when the compact upload action is icon-only', () => {
    const wrapper = mount(FileTransferUploader, {
      props: { presentation: 'button', uploadButtonIconName: 'swap', uploadButtonTitle: '替换图片' },
    });

    expect(wrapper.find('.file-transfer-uploader__choose-button').classes()).toContain(
      'ui-button--icon-only',
    );
  });

  it('shows caller-provided guidance before file selection', () => {
    const wrapper = mount(FileTransferUploader, {
      props: { dropzoneHint: '建议上传 128 × 128 px 的图片，最大 512 KB' },
    });

    expect(wrapper.get('.file-transfer-uploader__drop-zone-hint').text()).toBe(
      '建议上传 128 × 128 px 的图片，最大 512 KB',
    );
  });

  it('does not expose a native file chooser while disabled', async () => {
    const wrapper = mount(FileTransferUploader, {
      props: { disabled: true },
    });
    const input = wrapper.find('input[type="file"]');
    const click = vi.spyOn(input.element as HTMLInputElement, 'click');

    await wrapper.find('.file-transfer-uploader__drop-zone').trigger('click');

    expect(input.attributes('disabled')).toBeDefined();
    expect(click).not.toHaveBeenCalled();
  });

  it('keeps the compact button presentation disabled', async () => {
    const wrapper = mount(FileTransferUploader, {
      props: { disabled: true, presentation: 'button' },
    });
    const input = wrapper.find('input[type="file"]');
    const click = vi.spyOn(input.element as HTMLInputElement, 'click');

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

  it('uses an accessible success icon and compact list-removal action after upload', async () => {
    const wrapper = mount(FileTransferUploader, {
      props: {
        uploadFile: async (file) => ({ file, payload: { id: 'file-1' }, response: { id: 'file-1' } }),
      },
    });
    const file = new File(['content'], 'summary.pdf', { type: 'application/pdf' });
    const input = wrapper.get('input[type="file"]');

    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] });
    await input.trigger('change');
    await vi.waitFor(() => expect(wrapper.find('[aria-label="上传完成"]').exists()).toBe(true));

    expect(wrapper.find('.file-transfer-uploader__completed-icon svg').exists()).toBe(true);
    const remove = wrapper.get('.file-transfer-uploader__remove-button');
    expect(remove.attributes('aria-label')).toBe('从上传列表移除');
    expect(remove.attributes('title')).toBe('从上传列表移除');
    expect(remove.classes()).toContain('ui-button--icon-only');
    await remove.trigger('click');
    expect(wrapper.find('.file-transfer-uploader__item').exists()).toBe(false);
  });
});
