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
