import type { ModulePageEnhancement, ModulePageFormContributionState } from '@muyun/dynamic-page-runtime';
import TenantApplicationsDetailSection from './tenant/TenantApplicationsDetailSection.vue';
import TenantApplicationConfigurationDrawer from './tenant/TenantApplicationConfigurationDrawer.vue';
import TenantBrandingFormSection from './tenant/TenantBrandingFormSection.vue';

/**
 * IAM-specific presentation around the descriptor-owned tenant CRUD surface.
 * Standard records remain owned by ModulePageHost; this contribution only
 * renders the tenant application's authorized child-resource capability.
 */
export const tenantModulePageEnhancement: ModulePageEnhancement = {
  id: 'iam-tenant-standard-page-enhancement',
  target: { moduleAlias: 'iam.tenant' },
  form: {
    contributions: [
      {
        key: 'tenant-branding-heading',
        component: TenantBrandingFormSection,
        location: { surface: 'flat-main', fieldName: 'workbenchBrandMode', placement: 'before' },
      },
    ],
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
        imageUploadHint: ({ draft }: ModulePageFormContributionState) =>
          draft.workbenchBrandMode === 'logoOnly'
            ? '纯 Logo 模式支持横向或正方形图片（最大 512 KB）'
            : 'Logo + 标题模式仅支持正方形图片（建议 128 × 128 px，最大 512 KB）',
        imageUploadAdvisory: ({ draft }: ModulePageFormContributionState) =>
          draft.workbenchBrandMode === 'logoOnly' ? undefined : validateSquareTenantLogo,
      })),
    ],
  },
  detail: {
    actions: [
      {
        key: 'tenant-configure-applications',
        title: '配置应用',
        state: () => ({ visible: true }),
        run({ openDrawer }) {
          openDrawer({
            title: '配置应用',
            width: 760,
            component: TenantApplicationConfigurationDrawer,
          });
        },
      },
    ],
    sections: [
      {
        key: 'tenant-applications',
        title: '已开通应用',
        component: TenantApplicationsDetailSection,
      },
    ],
  },
};

async function validateSquareTenantLogo(file: File) {
  if (!file.type.startsWith('image/') || typeof Image === 'undefined') {
    return 'Logo + 标题模式需要可读取的正方形图片。';
  }
  try {
    const { width, height } = await imageDimensionsOf(file);
    const ratio = width / height;
    return ratio >= 0.9 && ratio <= 1.1
      ? undefined
      : `“${file.name}”为 ${width} × ${height} px；Logo + 标题模式仅允许上传正方形 Logo。`;
  } catch {
    return '无法读取图片尺寸，请选择正方形 PNG、JPG 或 GIF 图片。';
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
