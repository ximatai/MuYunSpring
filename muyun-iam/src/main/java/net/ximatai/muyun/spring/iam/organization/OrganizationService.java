package net.ximatai.muyun.spring.iam.organization;

import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.TenantActiveScopedService;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.platform.DataScopeFieldMapping;
import net.ximatai.muyun.spring.common.platform.OrganizationHierarchyService;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.OrganizationCreationProvisioner;
import net.ximatai.muyun.spring.common.util.Preconditions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrganizationService extends TenantActiveScopedService<Organization> implements
        SoftDeleteAbility<Organization>,
        EnableAbility<Organization>,
        TreeAbility<Organization>,
        ReferenceAbility<Organization>,
        DataScopeAbility<Organization>,
        OrganizationHierarchyService {

    public static final String MODULE_ALIAS = "iam.organization";
    private static final DataScopeFieldMapping DATA_SCOPE_FIELD_MAPPING = DataScopeFieldMapping.of(null, "id", null);
    private final ObjectProvider<OrganizationCreationProvisioner> creationProvisioners;

    public OrganizationService(OrganizationDao dao, ActiveTenantVerifier verifier) {
        this(dao, verifier, null);
    }

    @Autowired
    public OrganizationService(OrganizationDao dao, ActiveTenantVerifier verifier,
                               ObjectProvider<OrganizationCreationProvisioner> creationProvisioners) {
        super(MODULE_ALIAS, Organization.class, dao, verifier);
        this.creationProvisioners = creationProvisioners;
    }

    @Override
    public DataScopeFieldMapping dataScopeFieldMapping() {
        return DATA_SCOPE_FIELD_MAPPING;
    }

    @Override
    public void normalizeBeforeMutation(Organization organization) {
        organization.setCode(Preconditions.requireText(organization.getCode(), "organizationCode"));
    }

    @Override
    public void afterInsert(String id, Organization organization) {
        notifyCreationProvisioners(organization.getTenantId(), id);
    }

    /** Reconciles default resources for an existing, non-deleted organization in the current active tenant. */
    public void provisionOrganization(String organizationId) {
        String id = Preconditions.requireText(organizationId, "organizationId");
        inMutationTransaction(() -> {
            String tenantId = requireActiveTenantMutationContext();
            Organization organization = selectActiveRaw(id);
            if (organization == null || !tenantId.equals(organization.getTenantId())) {
                throw new PlatformException("Organization does not exist in current tenant: " + id);
            }
            notifyCreationProvisioners(tenantId, id);
            return null;
        });
    }

    private void notifyCreationProvisioners(String tenantId, String organizationId) {
        if (creationProvisioners != null) {
            creationProvisioners.orderedStream()
                    .forEach(provisioner -> provisioner.afterOrganizationCreated(tenantId, organizationId));
        }
    }

    @Override
    public List<String> organizationIdsFromSelfToRoot(String organizationId) {
        return ancestorIdsAndSelf(organizationId).reversed();
    }

}
