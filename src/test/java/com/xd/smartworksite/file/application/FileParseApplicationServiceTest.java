package com.xd.smartworksite.file.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.FileParseRecord;
import com.xd.smartworksite.file.domain.FileStatus;
import com.xd.smartworksite.file.dto.FileParseRequest;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.infra.StorageObject;
import com.xd.smartworksite.file.repository.FileObjectRepository;
import com.xd.smartworksite.file.repository.FileParseRecordRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FileParseApplicationServiceTest {

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
    void createParseFailsFastWhenInsertedRecordCannotBeReadBack() {
        FileParseApplicationService service = newService(new MissingReadBackParseRecordRepository());
        FileParseRequest request = new FileParseRequest();
        request.setProjectId(1L);

        assertThatThrownBy(() -> service.createParse(99L, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getMessage()).contains("file parse record is not readable"));
    }

    private FileParseApplicationService newService(FileParseRecordRepository parseRecordRepository) {
        return new FileParseApplicationService(
                new SingleFileObjectRepository(),
                parseRecordRepository,
                mock(FileParseWorker.class),
                new NoopStorageAdapter(),
                new FileProperties(),
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

    private static class SingleFileObjectRepository implements FileObjectRepository {
        @Override public FileObject insert(FileObject fileObject) { return fileObject; }
        @Override public List<FileObject> findPage(com.xd.smartworksite.file.dto.FileQueryRequest request) { return List.of(); }
        @Override public Optional<FileObject> findById(Long fileId) {
            FileObject file = new FileObject();
            file.setId(fileId);
            file.setProjectId(1L);
            file.setFileName("manual.pdf");
            file.setFileExt("pdf");
            file.setContentType("application/pdf");
            file.setFileHash("hash");
            file.setStatus(FileStatus.ACTIVE.name());
            return Optional.of(file);
        }
        @Override public int markDeleted(Long fileId, String status) { return 0; }
    }

    private static class MissingReadBackParseRecordRepository implements FileParseRecordRepository {
        @Override public FileParseRecord insert(FileParseRecord record) { record.setId(700L); return record; }
        @Override public Optional<FileParseRecord> findById(Long recordId) { return Optional.empty(); }
        @Override public List<FileParseRecord> findByFileId(Long projectId, Long fileId) { return List.of(); }
        @Override public Optional<FileParseRecord> findLatestByFileId(Long projectId, Long fileId) { return Optional.empty(); }
        @Override public Optional<FileParseRecord> findLatestSuccessfulByFileId(Long projectId, Long fileId) { return Optional.empty(); }
        @Override public Optional<FileParseRecord> findReusable(Long projectId, Long fileId, String sourceFileHash, String resultFormat) { return Optional.empty(); }
        @Override public int updateRunning(Long recordId, String stage, int progress) { return 0; }
        @Override public int updateSucceeded(FileParseRecord record) { return 0; }
        @Override public int updateFailed(Long recordId, String stage, String errorMessage) { return 0; }
    }

    private static class NoopStorageAdapter implements StorageAdapter {
        @Override public StorageObject upload(String objectName, InputStream inputStream, long size, String contentType) {
            return new StorageObject(objectName, "test-bucket", contentType, size);
        }
        @Override public InputStream openObject(String objectName) { return InputStream.nullInputStream(); }
        @Override public String createAccessUrl(String objectName, Duration expire) { return "http://127.0.0.1/" + objectName; }
        @Override public void delete(String objectName) {}
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
}
