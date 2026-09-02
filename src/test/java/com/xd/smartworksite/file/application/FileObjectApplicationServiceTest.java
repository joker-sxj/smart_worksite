package com.xd.smartworksite.file.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.dto.FileQueryRequest;
import com.xd.smartworksite.file.dto.FileUploadRequest;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.infra.StorageObject;
import com.xd.smartworksite.file.repository.FileObjectRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileObjectApplicationServiceTest {

    @BeforeEach
    void setUpSecurityContext() {
        UserPrincipal principal = new UserPrincipal(1L, "admin", List.of("PLATFORM_ADMIN"), List.of(), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadFailsFastWhenOriginalFilenameIsBlank() {
        FileObjectApplicationService service = newService();
        FileUploadRequest request = new FileUploadRequest();
        request.setProjectId(1L);
        request.setBizType("KNOWLEDGE_DOC");
        request.setFile(new MockMultipartFile("file", "", "text/plain", "content".getBytes()));

        assertThatThrownBy(() -> service.upload(request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getMessage()).contains("file original filename is required"));
    }

    @Test
    void uploadFailsFastAndCleansObjectWhenInsertedRecordCannotBeReadBack() {
        EmptyFileObjectRepository repository = new EmptyFileObjectRepository();
        CapturingStorageAdapter storageAdapter = new CapturingStorageAdapter();
        FileObjectApplicationService service = newService(repository, storageAdapter);
        FileUploadRequest request = new FileUploadRequest();
        request.setProjectId(1L);
        request.setBizType("KNOWLEDGE_DOC");
        request.setFile(new MockMultipartFile("file", "manual.txt", "text/plain", "content".getBytes()));

        assertThatThrownBy(() -> service.upload(request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getMessage()).contains("uploaded file record is not readable"));

        assertThat(storageAdapter.deletedObjectNames).hasSize(1);
    }

    @Test
    void uploadAcceptsCsvAndTsvContentTypes() {
        FileObjectRepository repository = new UploadFileObjectRepository();
        FileObjectApplicationService service = newService(repository, new CapturingStorageAdapter(),
                List.of("text/csv", "text/tab-separated-values"));
        FileUploadRequest request = new FileUploadRequest();
        request.setProjectId(1L);
        request.setBizType("KNOWLEDGE_DOC");
        request.setFile(new MockMultipartFile("file", "risk.csv", "text/csv", "date,risk".getBytes()));

        assertThat(service.upload(request).getContentType()).isEqualTo("text/csv");

        request.setFile(new MockMultipartFile("file", "progress.tsv", "application/octet-stream", "date\tstatus".getBytes()));
        assertThat(service.upload(request).getContentType()).isEqualTo("text/tab-separated-values");
    }

    @Test
    void openFileContentChecksExpectedOwnershipAndReturnsClosableStream() throws Exception {
        FileObject fileObject = new FileObject();
        fileObject.setId(20L);
        fileObject.setProjectId(1L);
        fileObject.setBizId(10L);
        fileObject.setFileName("template.md");
        fileObject.setObjectName("templates/template.md");
        fileObject.setContentType("text/markdown");
        fileObject.setFileSize(7L);
        fileObject.setStatus("ACTIVE");
        FileObjectApplicationService service = newService(
                new SingleFileObjectRepository(fileObject),
                new CapturingStorageAdapter()
        );

        try (InputStream inputStream = service.openFileContent(20L, 1L, 10L).getInputStream()) {
            assertThat(inputStream.readAllBytes()).isEqualTo("content".getBytes());
        }

        assertThatThrownBy(() -> service.openFileContent(20L, 2L, 10L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getMessage()).contains("project mismatch"));
        assertThatThrownBy(() -> service.openFileContent(20L, 1L, 11L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getMessage()).contains("business binding mismatch"));
    }

    private FileObjectApplicationService newService() {
        return newService(new EmptyFileObjectRepository(), new CapturingStorageAdapter());
    }

    private FileObjectApplicationService newService(List<String> allowedContentTypes) {
        return newService(new EmptyFileObjectRepository(), new CapturingStorageAdapter(), allowedContentTypes);
    }

    private FileObjectApplicationService newService(FileObjectRepository repository, StorageAdapter storageAdapter,
                                                    List<String> allowedContentTypes) {
        FileProperties properties = new FileProperties();
        properties.setAllowedContentTypes(allowedContentTypes);
        return new FileObjectApplicationService(
                repository,
                storageAdapter,
                properties,
                new ObjectMapper(),
                new ProjectAccessApplicationService(projectRepository(), new EmptyProjectMemberMapper())
        );
    }

    private static class UploadFileObjectRepository extends EmptyFileObjectRepository {
        private FileObject saved;

        @Override public FileObject insert(FileObject fileObject) {
            fileObject.setId(99L);
            saved = fileObject;
            return fileObject;
        }

        @Override public Optional<FileObject> findById(Long fileId) {
            return saved != null && saved.getId().equals(fileId) ? Optional.of(saved) : Optional.empty();
        }
    }

    private FileObjectApplicationService newService(FileObjectRepository repository, StorageAdapter storageAdapter) {
        FileProperties properties = new FileProperties();
        properties.setAllowedContentTypes(List.of("text/plain"));
        return new FileObjectApplicationService(
                repository,
                storageAdapter,
                properties,
                new ObjectMapper(),
                new ProjectAccessApplicationService(projectRepository(), new EmptyProjectMemberMapper())
        );
    }

    private ProjectRepository projectRepository() {
        return new ProjectRepository() {
            @Override public List<Project> findPage(String keyword, String status) { return List.of(); }
            @Override public List<Project> findPageByProjectIds(String keyword, String status, List<Long> projectIds) { return List.of(); }
            @Override public Optional<Project> findById(Long projectId) {
                Project project = new Project();
                project.setId(projectId);
                project.setStatus("ENABLED");
                return Optional.of(project);
            }
            @Override public Optional<Project> findByProjectCode(String projectCode) { return Optional.empty(); }
            @Override public Project insert(Project project) { return project; }
            @Override public int update(Project project) { return 1; }
            @Override public int softDelete(Long projectId, Long updatedBy) { return 1; }
            @Override public int updateStatus(Long projectId, String status, Long updatedBy) { return 1; }
            @Override public int updateSettings(Long projectId, String settings, Long updatedBy) { return 1; }
            @Override public long countActiveMembers(Long projectId) { return 0; }
            @Override public long countKnowledgeBases(Long projectId) { return 0; }
            @Override public long countReports(Long projectId) { return 0; }
            @Override public long countDataSources(Long projectId) { return 0; }
            @Override public long countQaMessages(Long projectId) { return 0; }
            @Override public long countReviewRecords(Long projectId) { return 0; }
            @Override public long countOcrRecords(Long projectId) { return 0; }
            @Override public long sumFileStorageBytes(Long projectId) { return 0; }
        };
    }

    private static class EmptyProjectMemberMapper implements ProjectMemberMapper {
        @Override public List<ProjectMember> selectByProjectId(Long projectId) { return List.of(); }
        @Override public ProjectMember selectByProjectIdAndUserId(Long projectId, Long userId) { return null; }
        @Override public int countActiveMember(Long projectId, Long userId) { return 0; }
        @Override public int insert(ProjectMember member) { return 1; }
        @Override public int update(ProjectMember member) { return 1; }
        @Override public int deleteByProjectIdAndUserId(Long projectId, Long userId, Long operatorId) { return 1; }
        @Override public List<Long> selectProjectIdsByUserId(Long userId) { return List.of(); }
            @Override public List<ProjectMember> selectEnabledByUserId(Long userId) { return List.of(); }
}

    private static class EmptyFileObjectRepository implements FileObjectRepository {
        @Override public FileObject insert(FileObject fileObject) { fileObject.setId(99L); return fileObject; }
        @Override public List<FileObject> findPage(FileQueryRequest request) { return List.of(); }
        @Override public Optional<FileObject> findById(Long fileId) { return Optional.empty(); }
        @Override public int markDeleted(Long fileId, String status) { return 0; }
    }

    private static class SingleFileObjectRepository extends EmptyFileObjectRepository {
        private final FileObject fileObject;

        private SingleFileObjectRepository(FileObject fileObject) {
            this.fileObject = fileObject;
        }

        @Override
        public Optional<FileObject> findById(Long fileId) {
            return fileObject.getId().equals(fileId) ? Optional.of(fileObject) : Optional.empty();
        }
    }

    private static class CapturingStorageAdapter implements StorageAdapter {
        private final List<String> deletedObjectNames = new ArrayList<>();

        @Override public StorageObject upload(String objectName, InputStream inputStream, long size, String contentType) {
            return new StorageObject(objectName, "test-bucket", contentType, size);
        }
        @Override public InputStream openObject(String objectName) { return new ByteArrayInputStream("content".getBytes()); }
        @Override public String createAccessUrl(String objectName, Duration expire) { return "http://127.0.0.1/" + objectName; }
        @Override public void delete(String objectName) { deletedObjectNames.add(objectName); }
    }
}
