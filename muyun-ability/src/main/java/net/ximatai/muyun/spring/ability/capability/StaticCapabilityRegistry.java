package net.ximatai.muyun.spring.ability.capability;

import java.util.List;

/** Registry of source-neutral capability facts visible to the static service compiler. */
public interface StaticCapabilityRegistry {
    List<? extends StaticCapabilityModule> staticModules();
}
