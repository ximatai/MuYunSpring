package net.ximatai.muyun.spring.platform.module;

import java.util.List;

/**
 * Supplies a validated declaration snapshot without exposing delivery-specific descriptors.
 * Runtime consumers must share a catalog, never invoke discovery scanners per request.
 */
public interface StaticModuleRegistrationSource {
    List<? extends StaticModuleRegistration> definitions();

    static StaticModuleRegistrationSource empty() {
        return List::of;
    }
}
