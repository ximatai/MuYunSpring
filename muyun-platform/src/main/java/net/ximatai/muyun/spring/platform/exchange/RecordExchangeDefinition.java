package net.ximatai.muyun.spring.platform.exchange;

import net.ximatai.muyun.spring.dynamic.metadata.FieldType;

import java.util.*;

/**
 * Compiled exchange facts. A producer retains ownership of reads, conversion and ordinary saves.
 */
public record RecordExchangeDefinition(String moduleAlias, List<Entity> entities) {
    public RecordExchangeDefinition {
        Objects.requireNonNull(moduleAlias);
        entities = List.copyOf(entities);
        if (entities.isEmpty() || entities.stream().filter(Entity::main).count() != 1)
            throw new IllegalArgumentException("Exchange requires exactly one root entity");
        if (entities.stream().map(Entity::alias).distinct().count() != entities.size())
            throw new IllegalArgumentException("Duplicate exchange entity");
    }

    public Entity main() {
        return entities.stream().filter(Entity::main).findFirst().orElseThrow();
    }

    public Entity entity(String alias) {
        return entities.stream()
                .filter(entity -> entity.alias().equals(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("File entity is unavailable"));
    }

    public record Entity(
            String alias,
            String title,
            String relationCode,
            String parentField,
            List<Field> fields) {
        public Entity {
            Objects.requireNonNull(alias);
            fields = List.copyOf(fields);
        }

        public boolean main() {
            return relationCode == null;
        }

        public Field field(String name) {
            return fields.stream()
                    .filter(field -> field.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("File field is unavailable"));
        }
    }

    public record Field(
            String name,
            String title,
            FieldType type,
            boolean required,
            boolean writable,
            boolean reference,
            boolean content,
            boolean companion) {}
}
