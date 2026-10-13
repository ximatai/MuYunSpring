package net.ximatai.muyun.spring.dynamic.metadata;

/** Missing runtime identity, distinct from an invalid module definition. */
public class UnknownDynamicModuleException extends ModuleDefinitionException {
    public UnknownDynamicModuleException(String moduleAlias) {
        super("unknown module alias: " + moduleAlias);
    }
}
