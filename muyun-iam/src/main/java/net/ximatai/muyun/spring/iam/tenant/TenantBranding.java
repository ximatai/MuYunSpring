package net.ximatai.muyun.spring.iam.tenant;

/** Tenant-owned workbench branding, resolved from stable platform-managed file asset ids. */
public record TenantBranding(String lightLogo, String darkLogo) {
    public static TenantBranding empty() {
        return new TenantBranding(null, null);
    }

}
