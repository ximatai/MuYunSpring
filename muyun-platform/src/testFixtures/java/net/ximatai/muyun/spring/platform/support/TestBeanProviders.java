package net.ximatai.muyun.spring.platform.support;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Explicit empty/single-bean containers for service constructor contract tests. */
public final class TestBeanProviders {
    private TestBeanProviders() { }

    public static <T> ObjectProvider<T> empty(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
    }

    public static <T> ObjectProvider<T> of(Class<T> type, T bean) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (bean != null) beans.addBean("dependency", bean);
        return beans.getBeanProvider(type);
    }
}
