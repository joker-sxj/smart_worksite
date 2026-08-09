package com.xd.smartworksite.file.controller;

import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.application.FileObjectContent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileObjectControllerTest {

    @Test
    void contentStreamsFileInlineThroughAuthenticatedBackend() {
        FileObjectApplicationService service = mock(FileObjectApplicationService.class);
        when(service.openFileContent(18L, null, null)).thenReturn(new FileObjectContent(
                18L,
                1L,
                null,
                "现场照片.jpg",
                "image/jpeg",
                4L,
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
        ));
        FileObjectController controller = new FileObjectController(service);

        ResponseEntity<InputStreamResource> response = controller.openFileContent(18L);

        verify(service).openFileContent(18L, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(4L);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("inline")
                .contains("filename*=");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, max-age=300");
    }
}
