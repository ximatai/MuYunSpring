/**
 * Metadata-driven records executed through the shared platform ability chain.
 *
 * <h2>Business integration</h2>
 * <p>Use {@link net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService} and
 * {@link net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord} to address dynamic records.
 * The facade locates the entity runtime and applies the platform execution contracts.
 * Static business modules continue to declare Java models and Services; they do not need
 * to construct dynamic metadata definitions.</p>
 *
 * <p>Entity Services, DAO implementations and runtime assembly remain available for platform
 * maintenance and diagnostics. Their presence here does not require business callers to
 * assemble a second CRUD chain or bypass permission, tenant and lifecycle processing.</p>
 */
package net.ximatai.muyun.spring.dynamic.runtime;
