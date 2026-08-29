package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.application.ApplicationReferenceContributor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class MetadataService extends AbstractAbilityService<Metadata> implements
        SoftDeleteAbility<Metadata>,
        EnableAbility<Metadata>,
        SortAbility<Metadata>,
        QueryAbility<Metadata>,
        ApplicationReferenceContributor {
    public static final String MODULE_ALIAS = "platform.metadata";
    public static final String DEFAULT_SCHEMA = "public";

    private final ObjectProvider<PlatformMetadataSchemaEnsureService> schemaEnsureServiceProvider;
    private final Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator;
    private final ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider;
    private final ObjectProvider<ModuleMetadataRelationService> relationServiceProvider;

    public MetadataService(BaseDao<Metadata, String> metadataDao) {
        this(metadataDao, provider(null), Optional.empty(), provider(null), provider(null));
    }

    public MetadataService(BaseDao<Metadata, String> metadataDao,
                           Optional<PlatformMetadataSchemaEnsureService> schemaEnsureService) {
        this(metadataDao, provider(schemaEnsureService == null ? null : schemaEnsureService.orElse(null)),
                Optional.empty(), provider(null), provider(null));
    }

    public MetadataService(BaseDao<Metadata, String> metadataDao,
                           Optional<PlatformMetadataSchemaEnsureService> schemaEnsureService,
                           Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        this(metadataDao,
                provider(schemaEnsureService == null ? null : schemaEnsureService.orElse(null)),
                runtimeRefreshCoordinator == null ? Optional.empty() : runtimeRefreshCoordinator,
                provider(null), provider(null));
    }

    @Autowired
    public MetadataService(BaseDao<Metadata, String> metadataDao,
                           ObjectProvider<PlatformMetadataSchemaEnsureService> schemaEnsureServiceProvider,
                           Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator,
                           ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider,
                           ObjectProvider<ModuleMetadataRelationService> relationServiceProvider) {
        super(MODULE_ALIAS, Metadata.class, metadataDao);
        this.schemaEnsureServiceProvider = Objects.requireNonNull(schemaEnsureServiceProvider,
                "schemaEnsureServiceProvider must not be null");
        this.runtimeRefreshCoordinator = Objects.requireNonNull(runtimeRefreshCoordinator,
                "runtimeRefreshCoordinator must not be null");
        this.referenceGuardProvider = Objects.requireNonNull(referenceGuardProvider, "referenceGuardProvider must not be null");
        this.relationServiceProvider = Objects.requireNonNull(relationServiceProvider,
                "relationServiceProvider must not be null");
    }

    @Override
    public void beforeDelete(String id) {
        ConfigurationReferenceDeletionGuard guard = referenceGuardProvider.getIfAvailable();
        if (guard != null) guard.assertCanDelete(ConfigurationReferenceTarget.METADATA, id);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, Metadata.class, java.util.List.of("id", "applicationAlias", "alias", "schemaName", "tableName", "dataScopeEnabled", "sortPartitionFields", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public String resourceKey() {
        return "metadata";
    }

    @Override
    public String resourceName() {
        return "元数据";
    }

    @Override
    public boolean hasReferenceTo(String applicationAlias) {
        return findOne(Criteria.of().eq("applicationAlias", applicationAlias)) != null;
    }

    @Override
    public void beforeInsert(Metadata metadata) {
        assertCapabilityDeclarationsAreGoverned(metadata, null);
        normalizeAndValidate(metadata);
    }

    @Override
    public void beforeUpdate(Metadata metadata) {
        assertCapabilityDeclarationsAreGoverned(metadata, metadata == null ? null : select(metadata.getId()));
        normalizeAndValidate(metadata);
    }

    @Override
    public void afterInsert(String id, Metadata metadata) {
        PlatformMetadataSchemaEnsureService schemaEnsureService = schemaEnsureService();
        if (schemaEnsureService != null) {
            schemaEnsureService.ensure(id);
        }
    }

    @Override
    public void afterUpdate(Metadata metadata, int updated) {
        PlatformMetadataSchemaEnsureService schemaEnsureService = schemaEnsureService();
        if (updated > 0 && schemaEnsureService != null && !MetadataCapabilityGovernanceMutationContext.isActive()) {
            schemaEnsureService.ensure(metadata.getId());
        }
    }

    @Override
    public void afterChanged(Metadata metadata) {
        PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator = runtimeRefreshCoordinator();
        if (runtimeRefreshCoordinator != null && !MetadataCapabilityGovernanceMutationContext.isActive()) {
            runtimeRefreshCoordinator.refreshByMetadataId(metadata.getId());
        }
    }

    private PlatformMetadataSchemaEnsureService schemaEnsureService() {
        return schemaEnsureServiceProvider.getIfAvailable();
    }

    private PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator() {
        return runtimeRefreshCoordinator.orElse(null);
    }

    private void normalizeAndValidate(Metadata metadata) {
        String applicationAlias = PlatformNameRules.requireApplicationAlias(metadata.getApplicationAlias());
        String alias = PlatformNameRules.requireIdentifier(metadata.getAlias(), "metadataAlias");
        metadata.setApplicationAlias(applicationAlias);
        metadata.setAlias(alias);
        if (metadata.getSchemaName() == null || metadata.getSchemaName().isBlank()) {
            metadata.setSchemaName(DEFAULT_SCHEMA);
        }
        PlatformNameRules.requireDatabaseName(metadata.getSchemaName(), "schemaName");
        if (metadata.getTableName() == null || metadata.getTableName().isBlank()) {
            metadata.setTableName(applicationAlias + "_" + alias);
        }
        PlatformNameRules.requireDatabaseName(metadata.getTableName(), "tableName");
        if (metadata.getDataScopeEnabled() == null) {
            metadata.setDataScopeEnabled(Boolean.FALSE);
        }
        if (isChildMetadata(metadata.getId())) {
            ModuleMetadataCapabilityPolicy.validateChildMetadataConfiguration(metadata);
        }
        if (metadata.getSortPartitionFields() != null) {
            LinkedHashSet<String> fields = new LinkedHashSet<>();
            for (String fieldName : metadata.getSortPartitionFields()) {
                fields.add(PlatformNameRules.requireIdentifier(fieldName, "sortPartitionField"));
            }
            metadata.setSortPartitionFields(fields);
        }
        if (metadata.getCapabilityDeclarations() != null) {
            LinkedHashSet<String> declarations = new LinkedHashSet<>();
            for (String declaration : metadata.getCapabilityDeclarations()) {
                declarations.add(DynamicMetadataCapabilityPolicy.requireSupportedDeclaration(declaration));
            }
            metadata.setCapabilityDeclarations(declarations);
        }
        rejectDuplicateMetadataAlias(metadata);
        rejectDuplicatePhysicalTable(metadata);
    }

    private void assertCapabilityDeclarationsAreGoverned(Metadata metadata, Metadata existing) {
        if (MetadataCapabilityGovernanceMutationContext.isActive()) return;
        if (metadata != null && metadata.getCapabilityDeclarations() != null) {
            throw new PlatformException("Metadata capability declarations require governed mutation");
        }
        if (existing != null && existing.getCapabilityDeclarations() != null
                && !Objects.equals(existing.getDataScopeEnabled(), metadata.getDataScopeEnabled())) {
            throw new PlatformException("Metadata dataScopeEnabled requires governed capability migration");
        }
    }

    private boolean isChildMetadata(String metadataId) {
        ModuleMetadataRelationService relationService = relationServiceProvider.getIfAvailable();
        return relationService != null && metadataId != null && !metadataId.isBlank()
                && relationService.count(Criteria.of().eq("metadataId", metadataId)
                        .eq("relationRole", RelationRole.CHILD)) > 0;
    }

    private void rejectDuplicateMetadataAlias(Metadata metadata) {
        rejectDuplicate(metadata, Criteria.of()
                .eq("applicationAlias", metadata.getApplicationAlias())
                .eq("alias", metadata.getAlias()),
                "metadataAlias must be unique within application: " + metadata.getAlias());
    }

    private void rejectDuplicatePhysicalTable(Metadata metadata) {
        rejectDuplicate(metadata, Criteria.of()
                .eq("schemaName", metadata.getSchemaName())
                .eq("tableName", metadata.getTableName()),
                "metadata physical table must be unique: " + metadata.getSchemaName() + "." + metadata.getTableName());
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
