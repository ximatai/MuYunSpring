package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.option.StaticOptionFieldValueValidator;
import net.ximatai.muyun.spring.ability.child.ChildAbilityResolver;
import net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleListener;
import net.ximatai.muyun.spring.ability.deletion.DeletionTransactionOperator;
import net.ximatai.muyun.spring.ability.reference.ReferenceDeletionGuard;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import net.ximatai.muyun.spring.ability.reference.ReferencedByResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadResolver;

public final class PlatformAbilityRuntime {
    private PlatformAbilityRuntime() {
    }

    public static void configureStaticOptionFieldValueValidator(StaticOptionFieldValueValidator validator) {
        PlatformAbilityDispatcher.setStaticOptionFieldValueValidator(validator);
    }

    public static void resetStaticOptionFieldValueValidator() {
        PlatformAbilityDispatcher.resetStaticOptionFieldValueValidator();
    }

    public static void configureEntitySaveLifecycleListener(EntitySaveLifecycleListener listener) {
        PlatformAbilityDispatcher.setEntitySaveLifecycleListener(listener);
    }

    public static void resetEntitySaveLifecycleListener() {
        PlatformAbilityDispatcher.resetEntitySaveLifecycleListener();
    }

    public static void configureDeletionLifecycleListener(DeletionLifecycleListener listener) {
        PlatformAbilityDispatcher.setDeletionLifecycleListener(listener);
    }

    public static void resetDeletionLifecycleListener() {
        PlatformAbilityDispatcher.resetDeletionLifecycleListener();
    }

    public static void configureDeletionTransactionOperator(DeletionTransactionOperator operator) {
        PlatformAbilityDispatcher.setDeletionTransactionOperator(operator);
    }

    public static void resetDeletionTransactionOperator() {
        PlatformAbilityDispatcher.resetDeletionTransactionOperator();
    }

    public static void configureReferenceDeletionGuard(ReferenceDeletionGuard guard) {
        PlatformAbilityDispatcher.setReferenceDeletionGuard(guard);
    }

    public static void resetReferenceDeletionGuard() {
        PlatformAbilityDispatcher.resetReferenceDeletionGuard();
    }

    public static void configureReferenceTargetResolver(ReferenceTargetResolver resolver) {
        PlatformAbilityDispatcher.setReferenceTargetResolver(resolver);
    }

    public static void resetReferenceTargetResolver() {
        PlatformAbilityDispatcher.resetReferenceTargetResolver();
    }

    public static ReferenceTargetResolver referenceTargetResolver() {
        return PlatformAbilityDispatcher.referenceTargetResolver();
    }

    public static void configureChildAbilityResolver(ChildAbilityResolver resolver) {
        PlatformAbilityDispatcher.setChildAbilityResolver(resolver);
    }

    public static void resetChildAbilityResolver() {
        PlatformAbilityDispatcher.resetChildAbilityResolver();
    }

    public static ChildAbilityResolver childAbilityResolver() {
        return PlatformAbilityDispatcher.childAbilityResolver();
    }

    public static void configureReferencedByResolver(ReferencedByResolver resolver) {
        PlatformAbilityDispatcher.setReferencedByResolver(resolver);
    }

    public static void resetReferencedByResolver() {
        PlatformAbilityDispatcher.resetReferencedByResolver();
    }

    public static void configureReferenceLoadResolver(ReferenceLoadResolver resolver) {
        PlatformAbilityDispatcher.setReferenceLoadResolver(resolver);
    }

    public static void resetReferenceLoadResolver() {
        PlatformAbilityDispatcher.resetReferenceLoadResolver();
    }
}
