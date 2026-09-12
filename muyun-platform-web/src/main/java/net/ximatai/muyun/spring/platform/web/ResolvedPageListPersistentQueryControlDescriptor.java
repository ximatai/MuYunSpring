package net.ximatai.muyun.spring.platform.web;

/** Source-neutral UI descriptor for one persistent list query control. */
public sealed interface ResolvedPageListPersistentQueryControlDescriptor
        permits ResolvedPageListFieldPersistentQueryControlDescriptor,
        ResolvedPageListExternalPersistentQueryControlDescriptor {
    String id();

    String title();

    PageListPersistentQueryControlDefinition.Source source();

    static ResolvedPageListPersistentQueryControlDescriptor from(
            PageListPersistentQueryControlDefinition definition) {
        return switch (definition) {
            case PageListFieldPersistentQueryControlDefinition field ->
                    new ResolvedPageListFieldPersistentQueryControlDescriptor(field.id(), field.title(),
                            field.fieldName(), field.operator(), field.defaultValues());
            case PageListExternalPersistentQueryControlDefinition external ->
                    new ResolvedPageListExternalPersistentQueryControlDescriptor(external.id(), external.title(),
                            external.externalCriteriaKey(), external.uiType(), (Boolean) external.defaultValue());
        };
    }
}
