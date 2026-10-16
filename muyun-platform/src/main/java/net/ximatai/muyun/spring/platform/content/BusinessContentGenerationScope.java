package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.ability.CrudAbility;
import java.util.function.Function;

/** Server-owned business entry scope. Implementations must reauthorize their live entry on every execution. */
public interface BusinessContentGenerationScope {
    String key();

    <T> T withAccess(Reference reference, String moduleAlias, BusinessContentService.Location location,
                     String field, Function<CrudAbility<?>, T> work);

    /** Captured by a trusted adapter, never accepted as a browser request DTO or permission snapshot. */
    record Reference(String adapter, String version, String payload) {
        public Reference {
            if (adapter == null || adapter.isBlank() || adapter.length() > 128
                    || version == null || version.isBlank() || version.length() > 128
                    || payload == null || payload.length() > 16_384)
                throw new IllegalArgumentException("Invalid business content execution scope");
        }
    }
}
