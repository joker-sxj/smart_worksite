package com.xd.smartworksite.template.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.dto.TemplatePreviewFile;
import com.xd.smartworksite.template.infra.TemplateFileSupport;
import com.xd.smartworksite.file.infra.DocumentFormatDetector;
import com.xd.smartworksite.template.repository.TemplateRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayInputStream;

@Service
public class TemplatePreviewApplicationService {

    private final TemplateRepository templateRepository;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final FileObjectApplicationService fileObjectApplicationService;

    public TemplatePreviewApplicationService(TemplateRepository templateRepository,
                                             ProjectAccessApplicationService projectAccessApplicationService,
                                             FileObjectApplicationService fileObjectApplicationService) {
        this.templateRepository = templateRepository;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.fileObjectApplicationService = fileObjectApplicationService;
    }

    public TemplatePreviewFile openPreview(Long templateId) {
        Template template = requireTemplate(templateId);
        projectAccessApplicationService.requireProjectAccess(template.getProjectId());
        FileObjectContent file = fileObjectApplicationService.openFileContent(
                template.getFileId(), template.getProjectId(), template.getId());
        byte[] bytes;
        try {
            bytes = file.getInputStream().readAllBytes();
        } catch (IOException ex) {
            closeQuietly(file);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取模板文件失败");
        }
        String actualFormat = DocumentFormatDetector.detect(bytes);
        if ("unknown".equals(actualFormat)) {
            actualFormat = TemplateFileSupport.extension(file.getFileName());
        }
        if (!TemplateFileSupport.isSupportedExtension(actualFormat)) {
            closeQuietly(file);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported template preview format: " + actualFormat);
        }
        String previewFileName = file.getFileName();
        if (!actualFormat.equals(TemplateFileSupport.extension(previewFileName))) {
            previewFileName = TemplateFileSupport.replaceExtension(previewFileName, actualFormat);
        }
        String contentType = TemplateFileSupport.contentTypeForExtension(actualFormat);
        closeQuietly(file);
        return new TemplatePreviewFile(
                previewFileName,
                contentType,
                bytes.length,
                new ByteArrayInputStream(bytes)
        );
    }

    private Template requireTemplate(Long templateId) {
        if (templateId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "模板ID不能为空");
        }
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "模板不存在"));
    }

    private void closeQuietly(FileObjectContent file) {
        try {
            file.getInputStream().close();
        } catch (IOException ignored) {
        }
    }
}
