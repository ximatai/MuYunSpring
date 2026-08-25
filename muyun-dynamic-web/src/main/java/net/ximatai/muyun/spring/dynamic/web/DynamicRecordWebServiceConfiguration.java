package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.platform.web.DynamicRelationProjectionReadService;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;
import net.ximatai.muyun.spring.platform.web.ListQuerySummaryRuntime;
import net.ximatai.muyun.spring.platform.web.PlatformModuleRuntimeContextService;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentAccessService;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentService;
import net.ximatai.muyun.spring.platform.code.CodeBusinessPreviewService;
import net.ximatai.muyun.spring.platform.duplicate.RecordDuplicateCheckService;
import net.ximatai.muyun.spring.platform.generation.ReferenceRecordGenerationFacade;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformRecordNavigationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DynamicRecordWebServiceConfiguration {
    @Bean
    DynamicRecordQueryServices dynamicRecordQueryServices(
            ObjectProvider<PlatformPageConfigSnapshotService> pageConfigSnapshotService,
            ObjectProvider<PlatformQueryItemService> queryItemService,
            ObjectProvider<ModuleMetadataFieldService> moduleMetadataFieldService,
            ObjectProvider<FieldUiControlService> fieldUiControlService,
            ObjectProvider<FieldUiControlBindingService> fieldUiControlBindingService,
            DynamicRelationProjectionReadService relationProjectionReadService,
            ObjectProvider<ModuleExecutionPlanCatalog> executionPlanCatalog,
            ObjectProvider<ListQuerySummaryRuntime> listQuerySummaryRuntime,
            ObjectProvider<PlatformModuleRuntimeContextService> runtimeContextService) {
        return new DynamicRecordQueryServices(
                pageConfigSnapshotService.getIfAvailable(),
                queryItemService.getIfAvailable(),
                moduleMetadataFieldService.getIfAvailable(),
                fieldUiControlService.getIfAvailable(),
                fieldUiControlBindingService.getIfAvailable(),
                relationProjectionReadService,
                executionPlanCatalog.getIfAvailable(), listQuerySummaryRuntime.getIfAvailable(), runtimeContextService.getIfAvailable());
    }

    @Bean
    DynamicRecordAttachmentServices dynamicRecordAttachmentServices(
            ObjectProvider<RecordAttachmentService> attachmentService,
            ObjectProvider<RecordAttachmentAccessService> attachmentAccessService) {
        return new DynamicRecordAttachmentServices(
                attachmentService.getIfAvailable(), attachmentAccessService.getIfAvailable());
    }

    @Bean
    DynamicRecordActionServices dynamicRecordActionServices(
            ObjectProvider<CodeBusinessPreviewService> codeBusinessPreviewService,
            ObjectProvider<ReferenceRecordGenerationFacade> referenceRecordGenerationFacade,
            ObjectProvider<RecordDuplicateCheckService> duplicateCheckService,
            ObjectProvider<PlatformRecordNavigationService> navigationService) {
        return new DynamicRecordActionServices(
                codeBusinessPreviewService.getIfAvailable(),
                referenceRecordGenerationFacade.getIfAvailable(),
                duplicateCheckService.getIfAvailable(),
                navigationService.getIfAvailable());
    }
}
