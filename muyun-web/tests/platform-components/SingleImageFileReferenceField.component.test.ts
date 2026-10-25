import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import type { ModuleContext } from '@/web-core';
import type { ResolvedFileReferenceFieldDescriptor } from '@/web-contracts';
import FileTransferUploader from '@/platform-components/FileTransferUploader.vue';
import SingleImageFileReferenceField from '@/platform-components/SingleImageFileReferenceField.vue';

const singleInlineImage: ResolvedFileReferenceFieldDescriptor = {
  fieldRef: { fieldName: 'logoAssetId' },
  allowedMediaTypes: ['image/png'],
  maxFileSizeBytes: 512 * 1024,
  maxFiles: 1,
  storagePolicy: 'DATABASE_INLINE',
  uploadAvailable: true,
  readAvailable: true,
};

it('keeps image upload guidance visible before a file is selected', () => {
  const wrapper = mount(SingleImageFileReferenceField, {
    props: {
      label: 'Logo',
      value: undefined,
      record: { alias: 'tenant-a' },
      context: { moduleAlias: 'iam.tenant' } as ModuleContext<unknown>,
      definition: singleInlineImage,
      uploadHint: '仅支持正方形图片（建议 128 × 128 px，最大 512 KB）',
    },
  });

  expect(wrapper.findComponent(FileTransferUploader).props('dropzoneHint')).toBe(
    '仅支持正方形图片（建议 128 × 128 px，最大 512 KB）',
  );
  expect(wrapper.text()).toContain('仅支持正方形图片（建议 128 × 128 px，最大 512 KB）');
});

it('clears an existing image binding from the draft without deleting the asset immediately', async () => {
  const wrapper = mount(SingleImageFileReferenceField, {
    props: {
      label: 'Logo',
      value: 'logo-1',
      record: { alias: 'tenant-a' },
      context: { moduleAlias: 'iam.tenant' } as ModuleContext<unknown>,
      definition: singleInlineImage,
    },
  });

  const clearButton = wrapper.findAll('button').find((button) => button.attributes('aria-label') === '清除');
  expect(clearButton).toBeDefined();
  await clearButton!.trigger('click');
  expect(wrapper.emitted('update:value')?.at(-1)).toEqual([undefined]);
});

it('hides only the title while retaining named actions, including when editing is disabled', async () => {
  const wrapper = mount(SingleImageFileReferenceField, {
    props: {
      label: 'Logo',
      hideLabel: true,
      value: 'logo-1',
      record: { id: 'tenant-a' },
      context: { moduleAlias: 'iam.tenant' } as ModuleContext<unknown>,
      definition: singleInlineImage,
    },
  });
  try {
    expect(wrapper.find('.single-image-file-reference-field__label').exists()).toBe(false);
    expect(wrapper.get('button[aria-label="替换图片"]').isVisible()).toBe(true);
    expect(wrapper.get('button[aria-label="清除"]').isVisible()).toBe(true);
    await wrapper.setProps({ disabled: true });
    expect(wrapper.find('button[aria-label="清除"]').exists()).toBe(false);
    expect(wrapper.get('button[aria-label="替换图片"]').attributes('disabled')).toBeDefined();
    await wrapper.setProps({ hideLabel: false });
    expect(wrapper.get('.single-image-file-reference-field__label').text()).toBe('Logo');
  } finally {
    wrapper.unmount();
  }
});
