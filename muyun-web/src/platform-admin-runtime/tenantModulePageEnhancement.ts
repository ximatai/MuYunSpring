import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import type { ModulePageEnhancement, ModulePageFormContributionState } from '@muyun/dynamic-page-runtime';
import TenantApplicationsDetailSection from './tenant/TenantApplicationsDetailSection.vue';
import TenantApplicationConfigurationDrawer from './tenant/TenantApplicationConfigurationDrawer.vue';

/**
 * IAM-specific presentation around the descriptor-owned tenant CRUD surface.
 * Standard records remain owned by ModulePageHost; this contribution only
 * renders the tenant application's authorized child-resource capability.
 */
export const tenantModulePageEnhancement: ModulePageEnhancement = {
  id: 'iam-tenant-standard-page-enhancement',
  target: { moduleAlias: 'iam.tenant' },
  form: {
    contributions: [],
    fieldPolicies: [
      {
        fieldName: 'workbenchTitle',
        visible: ({ draft }: ModulePageFormContributionState) => draft.workbenchBrandMode !== 'logoOnly',
      },
      {
        fieldName: 'workbenchSubtitle',
        visible: ({ draft }: ModulePageFormContributionState) => draft.workbenchBrandMode !== 'logoOnly',
      },
      ...['lightLogoAssetId', 'darkLogoAssetId'].map((fieldName) => ({
        fieldName,
        imageUploadHint: ({ draft }: ModulePageFormContributionState, language = platformLanguageContext) =>
          draft.workbenchBrandMode === 'logoOnly'
            ? language.t(
                'platform.entry.logo-only-mode-supports-landscape-or-square-images-up-to-512-kb',
                '纯 Logo 模式支持横向或正方形图片（最大 512 KB）',
              )
            : language.t(
                'platform.entry.logo-and-title-mode-requires-square-images-recommended-128-128-px-up-to-512-kb',
                'Logo + 标题模式仅支持正方形图片（建议 128 × 128 px，最大 512 KB）',
              ),
        imageUploadAdvisory: (
          { draft }: ModulePageFormContributionState,
          language = platformLanguageContext,
        ) =>
          draft.workbenchBrandMode === 'logoOnly'
            ? undefined
            : (file: File) => validateSquareTenantLogo(file, language),
      })),
    ],
  },
  detail: {
    actions: [
      {
        key: 'tenant-configure-applications',
        title: '配置应用',
        titleKey: 'platform.entry.configure-applications',
        state: () => ({ visible: true }),
        run({ openDrawer }) {
          openDrawer({
            title: '配置应用',
            titleKey: 'platform.entry.configure-applications',
            width: 'wide',
            component: TenantApplicationConfigurationDrawer,
          });
        },
      },
    ],
    sections: [
      {
        key: 'tenant-applications',
        title: '已开通应用',
        titleKey: 'platform.entry.provisioned-applications',
        component: TenantApplicationsDetailSection,
      },
    ],
  },
};

async function validateSquareTenantLogo(file: File, language: LanguageContext) {
  if (!file.type.startsWith('image/') || typeof Image === 'undefined') {
    return language.t(
      'platform.entry.logo-and-title-mode-requires-a-readable-square-image',
      'Logo + 标题模式需要可读取的正方形图片。',
    );
  }
  try {
    const { width, height } = await imageDimensionsOf(file);
    const ratio = width / height;
    return ratio >= 0.9 && ratio <= 1.1
      ? undefined
      : language.t(
          'platform.entry.value-is-value-value-px-logo-and-title-mode-only-supports-square-logos',
          '“{value1}”为 {value2} × {value3} px；Logo + 标题模式仅允许上传正方形 Logo。',
          { value1: file.name, value2: width, value3: height },
        );
  } catch {
    return language.t(
      'platform.entry.unable-to-read-image-dimensions-select-a-square-png-jpg-or-gif-image',
      '无法读取图片尺寸，请选择正方形 PNG、JPG 或 GIF 图片。',
    );
  }
}

function imageDimensionsOf(file: File) {
  const objectUrl = URL.createObjectURL(file);
  return new Promise<{ width: number; height: number }>((resolve, reject) => {
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(objectUrl);
      resolve({ width: image.naturalWidth, height: image.naturalHeight });
    };
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error('无法读取图片尺寸'));
    };
    image.src = objectUrl;
  });
}
