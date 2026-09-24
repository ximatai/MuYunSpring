package net.ximatai.muyun.spring.ability;

/** Opaque instance identity returned through a service proxy without leaking or unwrapping its target. */
public final class MutationServiceIdentity {
    private final CrudAbility<?> service;

    MutationServiceIdentity(CrudAbility<?> service) { this.service = service; }

    @Override public boolean equals(Object other) {
        return other instanceof MutationServiceIdentity identity && service == identity.service;
    }

    @Override public int hashCode() { return System.identityHashCode(service); }
}
