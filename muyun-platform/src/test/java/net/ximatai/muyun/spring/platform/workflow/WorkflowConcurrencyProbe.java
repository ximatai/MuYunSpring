package net.ximatai.muyun.spring.platform.workflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class WorkflowConcurrencyProbe implements WorkflowModuleRecordGuard {
    volatile String submitRecord;
    final AtomicBoolean approvalQuery = new AtomicBoolean();
    CountDownLatch reached; CountDownLatch release;
    void reset() { submitRecord = null; approvalQuery.set(false); reached = new CountDownLatch(1); release = new CountDownLatch(1); }
    @Override public void beforeSubmit(WorkflowSubmitRequest request) {
        if (request.recordId().equals(submitRecord) && Thread.currentThread().getName().equals("first-submit")) await();
    }
    void await() { reached.countDown(); try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("pause timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); } }
    WorkflowTaskDao observe(WorkflowTaskDao delegate) {
        return (WorkflowTaskDao) Proxy.newProxyInstance(WorkflowTaskDao.class.getClassLoader(), new Class<?>[]{WorkflowTaskDao.class}, (proxy, method, arguments) -> {
            try {
                Object result = method.invoke(delegate, arguments);
                // Capture the first transaction's sibling snapshot while its task update is uncommitted.
                if (method.getName().equals("query") && Thread.currentThread().getName().equals("first-approval") && approvalQuery.compareAndSet(true, false)) await();
                return result;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean WorkflowConcurrencyProbe concurrencyProbe() { return new WorkflowConcurrencyProbe(); }
        @Bean static BeanPostProcessor taskQueryProbe(WorkflowConcurrencyProbe probe) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof WorkflowTaskDao tasks ? probe.observe(tasks) : bean;
                }
            };
        }
    }
}
