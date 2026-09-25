package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.GlobalScopedAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionTrigger;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.exception.ApplicationNotOpenedException;
import net.ximatai.muyun.spring.common.platform.TenantApplicationCatalog;
import net.ximatai.muyun.spring.platform.application.ApplicationReferenceContributor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;

@Service
public class TenantApplicationService extends AbstractAbilityService<TenantApplication> implements
        GlobalScopedAbility<TenantApplication>,
        RecycleBinAbility<TenantApplication>,
        ChildAbility<TenantApplication>,
        ApplicationReferenceContributor {
    public static final String MODULE_ALIAS = "iam.tenant_application";
    public static final String IAM_APPLICATION_ALIAS = "iam";

    private final TenantApplicationCatalog applicationCatalog;

    public TenantApplicationService(TenantApplicationDao dao, TenantApplicationCatalog applicationCatalog) {
        super(MODULE_ALIAS, TenantApplication.class, dao);
        this.applicationCatalog = Objects.requireNonNull(applicationCatalog, "applicationCatalog");
    }

    public List<String> openedApplicationAliases(String tenantId) {
        return list(Criteria.of().eq("tenantId", requireTenantAlias(tenantId)),
                PageRequest.of(1, Integer.MAX_VALUE)).stream()
                .map(TenantApplication::getApplicationAlias)
                .sorted()
                .toList();
    }

    public boolean isApplicationOpened(String tenantId, String applicationAlias) {
        String validTenantId = requireTenantAlias(tenantId);
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        return !list(Criteria.of().eq("tenantId", validTenantId).eq("applicationAlias", validApplicationAlias),
                PageRequest.of(1, 1)).isEmpty();
    }

    @Override
    public String resourceKey() {
        return "tenantApplication";
    }

    @Override
    public String resourceName() {
        return "租户应用开通记录";
    }

    @Override
    public boolean hasReferenceTo(String applicationAlias) {
        return findOne(Criteria.of().eq("applicationAlias", applicationAlias)) != null;
    }

    /** Returns whether a recorded entitlement is currently executable for the tenant. */
    public boolean isApplicationAvailable(String tenantId, String applicationAlias) {
        String validTenantId = requireTenantAlias(tenantId);
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        return isApplicationOpened(validTenantId, validApplicationAlias)
                && applicationCatalog.isEnabledForTenant(validApplicationAlias);
    }

    /** Lists only recorded applications that remain globally available to tenants. */
    public List<String> availableApplicationAliases(String tenantId) {
        return openedApplicationAliases(tenantId).stream()
                .filter(applicationAlias -> applicationCatalog.isEnabledForTenant(applicationAlias))
                .toList();
    }

    public void requireApplicationOpened(String tenantId, String applicationAlias) {
        String validTenantId = requireTenantAlias(tenantId);
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        if (!isApplicationAvailable(validTenantId, validApplicationAlias)) {
            throw new ApplicationNotOpenedException(validTenantId, validApplicationAlias);
        }
    }

    /** Ensures every tenant retains the IAM application required for tenant administration. */
    @Transactional
    public void ensureRequiredApplications(String tenantId) {
        String validTenantId = requireTenantAlias(tenantId);
        if (!isApplicationOpened(validTenantId, IAM_APPLICATION_ALIAS)) {
            TenantApplication tenantApplication = new TenantApplication();
            tenantApplication.setTenantId(validTenantId);
            tenantApplication.setApplicationAlias(IAM_APPLICATION_ALIAS);
            insert(tenantApplication);
        }
    }

    /** Reconciles the child rows that express a tenant's available applications. */
    @Transactional
    public void configureApplications(String tenantId, Collection<String> applicationAliases) {
        String validTenantId = requireTenantAlias(tenantId);
        Set<String> desiredAliases = new LinkedHashSet<>();
        if (applicationAliases != null) {
            for (String applicationAlias : applicationAliases) {
                desiredAliases.add(PlatformNameRules.requireApplicationAlias(applicationAlias));
            }
        }
        if (!desiredAliases.contains(IAM_APPLICATION_ALIAS)) {
            throw new IllegalArgumentException("iam application must remain opened for a tenant");
        }
        desiredAliases.forEach(this::requireEnabledTenantApplication);
        List<TenantApplication> current = list(Criteria.of().eq("tenantId", validTenantId),
                PageRequest.of(1, Integer.MAX_VALUE));
        for (TenantApplication tenantApplication : current) {
            if (!desiredAliases.remove(tenantApplication.getApplicationAlias())) {
                delete(tenantApplication.getId(), tenantApplication.getVersion());
            }
        }
        for (String applicationAlias : desiredAliases) {
            TenantApplication tenantApplication = new TenantApplication();
            tenantApplication.setTenantId(validTenantId);
            tenantApplication.setApplicationAlias(applicationAlias);
            insert(tenantApplication);
        }
    }

    public void normalizeBeforeMutation(TenantApplication tenantApplication) {
        tenantApplication.setTenantId(requireTenantAlias(tenantApplication.getTenantId()));
        tenantApplication.setApplicationAlias(PlatformNameRules.requireApplicationAlias(
                tenantApplication.getApplicationAlias()));
        tenantApplication.setTitle(tenantApplication.getApplicationAlias());
    }

    @Override
    public void beforeInsert(TenantApplication tenantApplication) {
        requireEnabledTenantApplication(tenantApplication.getApplicationAlias());
        tenantApplication.setId(idOf(tenantApplication.getTenantId(), tenantApplication.getApplicationAlias()));
        rejectDuplicate(tenantApplication, Criteria.of()
                        .eq("tenantId", tenantApplication.getTenantId())
                        .eq("applicationAlias", tenantApplication.getApplicationAlias()),
                "tenant application already exists: " + tenantApplication.getApplicationAlias());
    }

    @Override
    public void beforeUpdate(TenantApplication tenantApplication) {
        TenantApplication existing = select(tenantApplication.getId());
        if (existing == null) {
            return;
        }
        rejectChanged(existing, tenantApplication, "tenantId", TenantApplication::getTenantId);
        rejectChanged(existing, tenantApplication, "applicationAlias", TenantApplication::getApplicationAlias);
        requireEnabledTenantApplication(tenantApplication.getApplicationAlias());
    }

    @Override
    public void beforeDelete(String id, DeletionContext deletionContext) {
        TenantApplication existing = select(id);
        if (existing != null && IAM_APPLICATION_ALIAS.equals(existing.getApplicationAlias())
                && deletionContext.trigger() != DeletionTrigger.CASCADE) {
            throw new IllegalArgumentException("iam application must remain opened for a tenant");
        }
    }

    @Override
    public boolean isRecycleBinPurgeEnabled() {
        return true;
    }

    @Override
    public void beforeRecycleBinPurge(String id) {
        // Tenant applications only participate in a root tenant's purge tree.
    }

    private void requireEnabledTenantApplication(String applicationAlias) {
        applicationCatalog.requireEnabledForTenant(applicationAlias);
    }

    private String requireTenantAlias(String tenantId) {
        return PlatformNameRules.requireIdentifier(tenantId, "tenantAlias");
    }

    private String idOf(String tenantId, String applicationAlias) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + ":" + applicationAlias).getBytes(StandardCharsets.UTF_8));
            return "ta_" + java.util.HexFormat.of().formatHex(bytes).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
