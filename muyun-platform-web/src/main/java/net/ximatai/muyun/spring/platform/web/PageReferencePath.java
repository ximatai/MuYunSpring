package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceLoadPath;

/** A compiled page-only read projection. The dotted field is never a writable model field. */
public record PageReferencePath(String fieldName, ReferenceLoadPath loadPath) {
}
