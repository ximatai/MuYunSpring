package net.ximatai.muyun.spring.starter.configuration.filetransfer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** External configuration for the official MuYunFileServer transport adapter. */
@ConfigurationProperties("muyun.file-transfer.muyun-fileserver")
public class MuYunFileServerTransferProperties {
    private boolean enabled;
    private URI baseUrl;
    private String issuer;
    private String secret;
    /**
     * Explicit FileServer namespace for operations initiated in the system workspace.
     * Tenant users always use their own tenant id; system users are rejected unless this is set.
     */
    private String systemScopeId;
    private Duration uploadTokenTtl = Duration.ofMinutes(10);
    private Duration metadataTokenTtl = Duration.ofMinutes(1);
    private Duration promoteTokenTtl = Duration.ofMinutes(1);
    private Duration deleteTokenTtl = Duration.ofMinutes(1);
    private Duration previewTokenTtl = Duration.ofMinutes(5);
    private Duration downloadTokenTtl = Duration.ofMinutes(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getSystemScopeId() { return systemScopeId; }
    public void setSystemScopeId(String systemScopeId) { this.systemScopeId = systemScopeId; }
    public Duration getUploadTokenTtl() { return uploadTokenTtl; }
    public void setUploadTokenTtl(Duration uploadTokenTtl) { this.uploadTokenTtl = uploadTokenTtl; }
    public Duration getMetadataTokenTtl() { return metadataTokenTtl; }
    public void setMetadataTokenTtl(Duration metadataTokenTtl) { this.metadataTokenTtl = metadataTokenTtl; }
    public Duration getPromoteTokenTtl() { return promoteTokenTtl; }
    public void setPromoteTokenTtl(Duration promoteTokenTtl) { this.promoteTokenTtl = promoteTokenTtl; }
    public Duration getDeleteTokenTtl() { return deleteTokenTtl; }
    public void setDeleteTokenTtl(Duration deleteTokenTtl) { this.deleteTokenTtl = deleteTokenTtl; }
    public Duration getPreviewTokenTtl() { return previewTokenTtl; }
    public void setPreviewTokenTtl(Duration previewTokenTtl) { this.previewTokenTtl = previewTokenTtl; }
    public Duration getDownloadTokenTtl() { return downloadTokenTtl; }
    public void setDownloadTokenTtl(Duration downloadTokenTtl) { this.downloadTokenTtl = downloadTokenTtl; }
}
