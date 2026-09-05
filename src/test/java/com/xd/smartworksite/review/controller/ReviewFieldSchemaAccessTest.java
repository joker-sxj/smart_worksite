package com.xd.smartworksite.review.controller;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.review.application.ReviewApplicationService;
import com.xd.smartworksite.review.application.ReviewFieldSchemaService;
import com.xd.smartworksite.template.application.TemplateApplicationService;
import com.xd.smartworksite.template.dto.TemplateResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ReviewFieldSchemaAccessTest {
    @Test
    void checksProjectAccessAndRejectsTemplateFromAnotherProject() {
        ReviewApplicationService review = mock(ReviewApplicationService.class);
        ReviewFieldSchemaService schemas = mock(ReviewFieldSchemaService.class);
        ProjectAccessApplicationService access = mock(ProjectAccessApplicationService.class);
        TemplateApplicationService templates = mock(TemplateApplicationService.class);
        TemplateResponse template = new TemplateResponse(); template.setProjectId(2L); template.setTemplateCategory("REVIEW"); template.setStatus("ENABLED");
        when(templates.getTemplate(9L)).thenReturn(template);
        ReviewController controller = new ReviewController(review, schemas, access, templates);

        assertThrows(BusinessException.class, () -> controller.getFieldSchema(1L, 9L));
        verify(access).requireProjectAccess(1L);
        verifyNoInteractions(schemas);
    }
}
