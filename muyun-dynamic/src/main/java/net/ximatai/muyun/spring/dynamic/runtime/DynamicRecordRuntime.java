package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.DatabaseValueConverter;
import net.ximatai.muyun.spring.ability.CacheRegistry;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.reference.ReferenceDependencyRegistry;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class DynamicRecordRuntime implements AutoCloseable {
    private static final AtomicLong CACHE_NAMESPACE_SEQUENCE = new AtomicLong();
    private static final int CASCADE_BATCH_SIZE = 200;

    private final IDatabaseOperations<?> operations;
    private final DynamicModuleRegistry registry;
    private volatile Map<ReferenceTarget, List<DynamicInboundReference>> inboundReferences = Map.of();
    private final String cacheNamespacePrefix;
    private final DynamicFieldValueValidator fieldValueValidator;
    private final RuntimeEventPublisher eventPublisher;
    private final DynamicActionExecutorRegistry actionExecutorRegistry;
    private final DynamicActionTransactionOperator actionTransactionOperator;
    private final FieldCryptoProvider fieldCryptoProvider;
    private final FieldSigner fieldSigner;
    private final PlatformTimeService timeService;
    private final DatabaseValueConverter valueConverter;
    private final DynamicOptionLoadPopulator optionLoadPopulator;

    public DynamicRecordRuntime(IDatabaseOperations<?> operations) {
        this(builder(operations));
    }

    private DynamicRecordRuntime(Builder builder) {
        this.operations = Objects.requireNonNull(builder.operations, "operations must not be null");
        this.registry = Objects.requireNonNull(builder.registry, "registry must not be null");
        this.fieldValueValidator = Objects.requireNonNull(builder.fieldValueValidator,
                "fieldValueValidator must not be null");
        this.eventPublisher = Objects.requireNonNull(builder.eventPublisher, "eventPublisher must not be null");
        this.actionExecutorRegistry = Objects.requireNonNull(builder.actionExecutorRegistry,
                "actionExecutorRegistry must not be null");
        this.actionTransactionOperator = Objects.requireNonNull(builder.actionTransactionOperator,
                "actionTransactionOperator must not be null");
        this.fieldCryptoProvider = Objects.requireNonNull(builder.fieldCryptoProvider,
                "fieldCryptoProvider must not be null");
        this.fieldSigner = Objects.requireNonNull(builder.fieldSigner, "fieldSigner must not be null");
        this.timeService = Objects.requireNonNull(builder.timeService, "timeService must not be null");
        this.valueConverter = Objects.requireNonNull(builder.valueConverter, "valueConverter must not be null");
        this.optionLoadPopulator = Objects.requireNonNull(builder.optionLoadPopulator, "optionLoadPopulator must not be null");
        this.cacheNamespacePrefix = "dynamic-runtime-" + CACHE_NAMESPACE_SEQUENCE.incrementAndGet();
        rebuildInboundReferenceIndex();
    }

    public static Builder builder(IDatabaseOperations<?> operations) {
        return new Builder(operations);
    }

    public static final class Builder {
        private final IDatabaseOperations<?> operations;
        private DynamicModuleRegistry registry = new DynamicModuleRegistry();
        private DynamicFieldValueValidator fieldValueValidator = DynamicFieldValueValidator.NONE;
        private RuntimeEventPublisher eventPublisher = RuntimeEventPublisher.noop();
        private DynamicActionExecutorRegistry actionExecutorRegistry = DynamicActionExecutorRegistry.empty();
        private DynamicActionTransactionOperator actionTransactionOperator = DynamicActionTransactionOperator.none();
        private FieldCryptoProvider fieldCryptoProvider = FieldCryptoProvider.UNAVAILABLE;
        private FieldSigner fieldSigner = FieldSigner.UNAVAILABLE;
        private PlatformTimeService timeService = new PlatformTimeService();
        private DatabaseValueConverter valueConverter = DatabaseValueConverter.DEFAULT;
        private DynamicOptionLoadPopulator optionLoadPopulator = DynamicOptionLoadPopulator.NONE;

        private Builder(IDatabaseOperations<?> operations) {
            this.operations = Objects.requireNonNull(operations, "operations must not be null");
        }

        public Builder registry(DynamicModuleRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry must not be null");
            return this;
        }

        public Builder fieldValueValidator(DynamicFieldValueValidator fieldValueValidator) {
            this.fieldValueValidator = Objects.requireNonNull(fieldValueValidator,
                    "fieldValueValidator must not be null");
            return this;
        }

        public Builder eventPublisher(RuntimeEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher == null ? RuntimeEventPublisher.noop() : eventPublisher;
            return this;
        }

        public Builder actionExecutorRegistry(DynamicActionExecutorRegistry actionExecutorRegistry) {
            this.actionExecutorRegistry = actionExecutorRegistry == null
                    ? DynamicActionExecutorRegistry.empty()
                    : actionExecutorRegistry;
            return this;
        }

        public Builder actionTransactionOperator(DynamicActionTransactionOperator actionTransactionOperator) {
            this.actionTransactionOperator = actionTransactionOperator == null
                    ? DynamicActionTransactionOperator.none()
                    : actionTransactionOperator;
            return this;
        }

        public Builder fieldProtection(FieldCryptoProvider fieldCryptoProvider, FieldSigner fieldSigner) {
            this.fieldCryptoProvider = fieldCryptoProvider == null
                    ? FieldCryptoProvider.UNAVAILABLE
                    : fieldCryptoProvider;
            this.fieldSigner = fieldSigner == null ? FieldSigner.UNAVAILABLE : fieldSigner;
            return this;
        }

        public Builder timeService(PlatformTimeService timeService) {
            this.timeService = timeService == null ? new PlatformTimeService() : timeService;
            return this;
        }

        public Builder valueConverter(DatabaseValueConverter valueConverter) {
            this.valueConverter = valueConverter == null ? DatabaseValueConverter.DEFAULT : valueConverter;
            return this;
        }

        public Builder optionLoadPopulator(DynamicOptionLoadPopulator optionLoadPopulator) {
            this.optionLoadPopulator = optionLoadPopulator == null ? DynamicOptionLoadPopulator.NONE : optionLoadPopulator;
            return this;
        }

        public DynamicRecordRuntime build() {
            return new DynamicRecordRuntime(this);
        }
    }

    public DynamicRecordRuntime register(ModuleDefinition module) {
        registry.register(module);
        rebuildInboundReferenceIndex();
        return this;
    }

    public DynamicRecordRuntime refresh(ModuleDefinition module) {
        registry.refresh(module);
        rebuildInboundReferenceIndex();
        return this;
    }

    public void requireNotRegistered(String moduleAlias) {
        if (registry.containsModule(moduleAlias)) {
            throw new ModuleDefinitionException("duplicate module alias: " + moduleAlias);
        }
    }

    public DynamicModuleRegistry registry() {
        return registry;
    }

    public DynamicRecord newRecord(String moduleAlias, String entityAlias) {
        return new DynamicRecord(registry.requireEntity(moduleAlias, entityAlias));
    }

    public DynamicModuleDescriptor describe(String moduleAlias) {
        return registry.describe(moduleAlias);
    }

    public RuntimeEventPublisher eventPublisher() {
        return eventPublisher;
    }

    public DynamicActionExecutorRegistry actionExecutorRegistry() {
        return actionExecutorRegistry;
    }

    public DynamicActionTransactionOperator actionTransactionOperator() {
        return actionTransactionOperator;
    }

    public IDatabaseOperations<?> operations() {
        return operations;
    }

    public DynamicEntityService entityService(String moduleAlias, String entityAlias) {
        return entityService(moduleAlias, entityAlias, DynamicRecordLifecycle.NONE);
    }

    public java.util.Optional<net.ximatai.muyun.spring.ability.reference.ReferenceAbility<?>> referenceAbility(
            ReferenceTarget target) {
        if (target == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(entityService(target.moduleAlias(), target.entityAlias()).referenceAbility());
        } catch (ModuleDefinitionException ignored) {
            return java.util.Optional.empty();
        }
    }

    /** Resolves a declared dynamic outgoing reference for source-independent path reads. */
    public java.util.Optional<ReferencePlan> referencePlan(ReferenceTarget sourceTarget, String sourceField) {
        if (sourceTarget == null || sourceField == null || sourceField.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return registry.requireModule(sourceTarget.moduleAlias()).references().stream()
                    .filter(reference -> sourceTarget.entityAlias().equals(reference.sourceEntityAlias()))
                    .filter(reference -> sourceField.equals(reference.sourceField()))
                    .map(net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition::plan)
                    .findFirst();
        } catch (ModuleDefinitionException ignored) {
            return java.util.Optional.empty();
        }
    }

    public DynamicEntityService entityService(String moduleAlias, String entityAlias, DynamicRecordLifecycle lifecycle) {
        ModuleDefinition module = registry.requireModule(moduleAlias);
        EntityDefinition entity = registry.requireEntity(moduleAlias, entityAlias);
        return new DynamicEntityService(
                new DynamicRecordDao(operations, entity, valueConverter),
                moduleAlias,
                lifecycle,
                module,
                childEntityAliasCode -> entityService(moduleAlias, childEntityAliasCode),
                target -> entityService(target.moduleAlias(), target.entityAlias()),
                cacheNamespacePrefix,
                fieldValueValidator,
                fieldCryptoProvider,
                fieldSigner,
                timeService,
                optionLoadPopulator
        );
    }

    /** Checks dynamic referrers for an arbitrary platform reference target. */
    public void validateReferenceTargetDeletion(ReferenceTarget target, String targetId) {
        if (target == null || targetId == null || targetId.isBlank()) {
            return;
        }
        for (DynamicInboundReference inbound : inboundReferences.getOrDefault(target, List.of())) {
            var reference = inbound.reference();
            if (reference.integrity().onTargetUnavailable() != ReferenceTargetUnavailablePolicy.RESTRICT) {
                continue;
            }
            DynamicEntityService source = entityService(inbound.moduleAlias(), reference.sourceEntityAlias());
                boolean referenced = !source.list(Criteria.of().eq(reference.sourceField(), targetId),
                        PageRequest.of(1, 1)).isEmpty();
                if (referenced) {
                    throw new PlatformException("cannot make reference target unavailable " + target.qualifiedName()
                            + ": active records in " + inbound.moduleAlias()
                            + "." + reference.sourceField() + " still reference it");
                }
        }
    }

    /**
     * Deletes dynamic CASCADE referrers in the same deletion tree, then verifies
     * that no dynamic RESTRICT referrer remains.
     */
    public void cascadeReferenceTargetUnavailable(ReferenceTarget target,
                                                  String targetId,
                                                  DeletionContext context,
                                                  DeletionNode node) {
        if (target == null || targetId == null || targetId.isBlank()) {
            return;
        }
        for (DynamicInboundReference inbound : inboundReferences.getOrDefault(target, List.of())) {
            var reference = inbound.reference();
            if (reference.integrity().onTargetUnavailable()
                    != ReferenceTargetUnavailablePolicy.CASCADE_DELETE) {
                continue;
            }
            DynamicEntityService source = entityService(inbound.moduleAlias(), reference.sourceEntityAlias());
            java.util.Set<String> attemptedIds = new java.util.LinkedHashSet<>();
            while (true) {
                List<DynamicRecord> referrers = source.list(Criteria.of().eq(reference.sourceField(), targetId),
                        PageRequest.of(1, CASCADE_BATCH_SIZE));
                if (referrers.isEmpty()) {
                    break;
                }
                boolean progressed = false;
                for (DynamicRecord referrer : referrers) {
                    if (!attemptedIds.add(referrer.getId())) {
                        continue;
                    }
                    int deleted = source.delete(referrer.getId(), referrer.getVersion(),
                            context.child(node, source.getModuleAlias(), referrer.getId()));
                    progressed = progressed || deleted > 0;
                }
                if (!progressed) {
                    break;
                }
            }
        }
    }

    private void rebuildInboundReferenceIndex() {
        Map<ReferenceTarget, List<DynamicInboundReference>> index = new LinkedHashMap<>();
        for (ModuleDefinition module : registry.modules()) {
            for (var reference : module.references()) {
                index.computeIfAbsent(reference.target(), ignored -> new java.util.ArrayList<>())
                        .add(new DynamicInboundReference(module.moduleAlias(), reference));
            }
        }
        inboundReferences = index.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }

    private record DynamicInboundReference(String moduleAlias,
                                           net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition reference) {
    }

    public void clearCache() {
        CacheRegistry.clearNamespacePrefix(cacheNamespacePrefix);
        ReferenceDependencyRegistry.clearNamespacePrefix(cacheNamespacePrefix);
    }

    @Override
    public void close() {
        clearCache();
    }
}
