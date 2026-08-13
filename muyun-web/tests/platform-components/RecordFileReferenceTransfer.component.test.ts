import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import type { ModuleContext } from '@/web-core';
import type { ResolvedFileReferenceFieldDescriptor } from '@/web-contracts';
import FileTransferUploader from '@/platform-components/FileTransferUploader.vue';
import RecordFileReferenceTransfer from '@/platform-components/RecordFileReferenceTransfer.vue';

const singleInlineImage: ResolvedFileReferenceFieldDescriptor = {
  fieldRef: { fieldName: 'logoAssetId' },
  allowedMediaTypes: ['image/png'],
  maxFileSizeBytes: 512 * 1024,
  maxFiles: 1,
  storagePolicy: 'DATABASE_INLINE',
  uploadAvailable: true,
  readAvailable: true,
};

it('releases a completed single-image upload after binding so replacement remains available', async () => {
  const wrapper = mount(RecordFileReferenceTransfer, {
    props: {
      value: 'asset-old',
      record: { alias: 'tenant-a' },
      context: { moduleAlias: 'iam.tenant' } as ModuleContext<unknown>,
      definition: singleInlineImage,
      releaseCompletedUploadOnBind: true,
    },
  });
  const uploader = wrapper.findComponent(FileTransferUploader);

  uploader.vm.$emit('completed', {
    payload: { items: [{ id: 'asset-new' }] },
  });
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toEqual([['asset-new']]);
  expect(uploader.props('releasedCompletedFileIds')).toEqual(['asset-new']);
});
