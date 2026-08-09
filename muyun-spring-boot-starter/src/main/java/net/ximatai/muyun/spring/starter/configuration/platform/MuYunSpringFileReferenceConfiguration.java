package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.EntitySaveLifecycleListener;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceSaveLifecycleListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MuYunSpringFileReferenceConfiguration {
    @Bean
    FileReferenceSaveLifecycleListener fileReferenceSaveLifecycleListener(ObjectProvider<net.ximatai.muyun.spring.platform.attachment.FileTransferClient> clients) {
        return new FileReferenceSaveLifecycleListener(clients::getIfAvailable);
    }

    @Bean
    EntitySaveLifecycleRegistration entitySaveLifecycleRegistration(FileReferenceSaveLifecycleListener listener) {
        return new EntitySaveLifecycleRegistration(listener);
    }

    static final class EntitySaveLifecycleRegistration implements DisposableBean {
        EntitySaveLifecycleRegistration(EntitySaveLifecycleListener listener) { PlatformAbilityRuntime.configureEntitySaveLifecycleListener(listener); }
        @Override public void destroy() { PlatformAbilityRuntime.resetEntitySaveLifecycleListener(); }
    }
}
