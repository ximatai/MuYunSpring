package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadPolicy;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadRequest;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccess;
import net.ximatai.muyun.spring.platform.attachment.FileTransferAccessService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Standard, policy-gated ticket endpoint for form file-reference fields. */
@RestController
@RequestMapping("/{moduleAlias:[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+}/file-transfer")
public class FileReferenceUploadTicketWebController {
    private final List<FileReferenceUploadPolicy> policies;
    private final FileTransferAccessService transferAccessService;
    private final PlatformModuleRuntimeContextService runtimeContextService;

    public FileReferenceUploadTicketWebController(ObjectProvider<FileReferenceUploadPolicy> policies,
                                                   ObjectProvider<FileTransferAccessService> transferAccessService,
                                                   PlatformModuleRuntimeContextService runtimeContextService) {
        this.policies = policies.orderedStream().toList();
        this.transferAccessService = transferAccessService.getIfAvailable();
        this.runtimeContextService = runtimeContextService;
    }

    @PostMapping("/upload-ticket")
    public FileTransferAccess uploadTicket(@PathVariable String moduleAlias,
                                           @RequestBody FileReferenceUploadTicketRequest body) {
        if (transferAccessService == null) {
            throw new PlatformConfigurationException("file transfer is not configured for this application");
        }
        FileReferenceUploadRequest request = body.toPolicyRequest(moduleAlias);
        if (!runtimeContextService.declaresFileReference(request.moduleAlias(), request.relationCode(), request.fieldName())) {
            throw new PlatformException(PlatformErrorCodes.VALIDATION_FAILED, 400,
                    "file-reference field is not declared by module runtime: " + request.moduleAlias()
                            + "." + request.fieldName());
        }
        List<FileReferenceUploadPolicy> matchingPolicies = policies.stream().filter(candidate -> candidate.supportsField(
                request.moduleAlias(), request.relationCode(), request.fieldName())).toList();
        if (matchingPolicies.isEmpty()) {
            throw new PlatformConfigurationException("no file-reference upload policy is configured for "
                    + request.moduleAlias() + "." + request.fieldName());
        }
        if (matchingPolicies.size() > 1) {
            throw new PlatformConfigurationException("multiple file-reference upload policies are configured for "
                    + request.moduleAlias() + "." + request.fieldName());
        }
        FileReferenceUploadPolicy policy = matchingPolicies.getFirst();
        policy.authorize(request);
        return transferAccessService.issueUploadAccess();
    }
}
