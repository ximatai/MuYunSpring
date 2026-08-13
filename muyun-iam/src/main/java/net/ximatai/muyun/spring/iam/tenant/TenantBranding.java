package net.ximatai.muyun.spring.iam.tenant;

/** Tenant-owned workbench branding, resolved from stable platform-managed file asset ids and tenant configuration. */
public record TenantBranding(String lightLogo, String darkLogo, String mode, String title, String subtitle) {
    public static TenantBranding empty() {
        return new TenantBranding(null, null, null, null, null);
    }

}
