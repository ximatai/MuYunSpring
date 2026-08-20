import { mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import RecordDetailFields from '@/platform-components/RecordDetailFields.vue';
import RecordImageFileReferencePreview from '@/platform-components/RecordImageFileReferencePreview.vue';

describe('RecordDetailFields', () => {
  it('renders a declared single-image file reference as a preview instead of its file id', () => {
    const ImagePreviewStub = defineComponent({
      name: 'RecordImageFileReferencePreview',
      props: { value: { type: [String, Array], required: false, default: undefined } },
      template: '<figure data-testid="image-preview" />',
    });
    const wrapper = mount(RecordDetailFields, {
      props: {
        record: { lightLogoAssetId: '2290dc4a36d643e28ed8725523a0dbb1' },
        fields: new Map([
          [
            'lightLogoAssetId',
            {
              fieldRef: { fieldName: 'lightLogoAssetId' },
              label: '展示 Logo（默认）',
              fileReference: {
                fieldRef: { fieldName: 'lightLogoAssetId' },
                allowedMediaTypes: ['image/png'],
                maxFiles: 1,
                storagePolicy: 'DATABASE_INLINE' as const,
                uploadAvailable: true,
                readAvailable: true,
              },
            },
          ],
        ]),
        fileTransferContext: {} as never,
      },
      global: { stubs: { RecordImageFileReferencePreview: ImagePreviewStub } },
    });

    expect(wrapper.find('[data-testid="image-preview"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('2290dc4a36d643e28ed8725523a0dbb1');
  });

  it('keeps the latest image preview when the displayed record changes during an access request', async () => {
    let resolveFirst: ((value: { url: string }) => void) | undefined;
    let resolveSecond: ((value: { url: string }) => void) | undefined;
    const request = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<{ url: string }>((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<{ url: string }>((resolve) => {
            resolveSecond = resolve;
          }),
      );
    const definition = {
      fieldRef: { fieldName: 'logoAssetId' },
      allowedMediaTypes: ['image/png'],
      maxFiles: 1,
      storagePolicy: 'DATABASE_INLINE' as const,
      uploadAvailable: true,
      readAvailable: true,
    };
    const wrapper = mount(RecordImageFileReferencePreview, {
      props: {
        value: 'first-logo',
        record: { id: 'first' },
        context: { moduleAlias: 'iam.tenant', http: { request } } as never,
        definition,
      },
    });

    await nextTick();
    await wrapper.setProps({ value: 'second-logo', record: { id: 'second' } });
    await nextTick();
    resolveSecond?.({ url: 'https://example.test/second.png' });
    await Promise.resolve();
    await nextTick();
    resolveFirst?.({ url: 'https://example.test/first.png' });
    await Promise.resolve();
    await nextTick();

    expect(wrapper.get('img').attributes('src')).toBe('https://example.test/second.png');
  });

  it('leaves loading state when an in-flight image reference is cleared', async () => {
    let resolvePreview: ((value: { url: string }) => void) | undefined;
    const request = vi.fn().mockImplementation(
      () =>
        new Promise<{ url: string }>((resolve) => {
          resolvePreview = resolve;
        }),
    );
    const wrapper = mount(RecordImageFileReferencePreview, {
      props: {
        value: 'logo',
        record: { id: 'tenant-a' },
        context: { moduleAlias: 'iam.tenant', http: { request } } as never,
        definition: {
          fieldRef: { fieldName: 'logoAssetId' },
          allowedMediaTypes: ['image/png'],
          maxFiles: 1,
          storagePolicy: 'DATABASE_INLINE' as const,
          uploadAvailable: true,
          readAvailable: true,
        },
      },
    });

    await nextTick();
    expect(wrapper.text()).toContain('正在加载预览');
    await wrapper.setProps({ value: undefined });
    await nextTick();
    expect(wrapper.text()).toContain('图片预览不可用');
    resolvePreview?.({ url: 'https://example.test/stale.png' });
  });
});
