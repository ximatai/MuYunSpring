package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.common.platform.PlatformAction;

/** A trusted entry reopens live VIEW and the requested content-maintenance action for one owning text. */
@FunctionalInterface
public interface BusinessContentFieldAccess {
    BusinessContentService.FieldTarget require(PlatformAction action);
}
