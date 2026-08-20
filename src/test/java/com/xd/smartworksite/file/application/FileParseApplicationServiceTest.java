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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

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
        FileParseApplicationService service = newService(new SingleFileObjectRepository(),
                new MissingReadBackParseRecordRepository(), new NoopStorageAdapter());
        FileParseRequest request = new FileParseRequest();
        request.setProjectId(1L);

        assertThatThrownBy(() -> service.createParse(99L, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getMessage()).contains("file parse record is not readable"));
    }

    @ParameterizedTest
    @CsvSource({
            "risk-register.xlsx, xlsx, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, SPREADSHEET_TO_MARKDOWN",
            "site-plan.pptx, pptx, application/vnd.openxmlformats-officedocument.presentationml.presentation, PRESENTATION_TO_MARKDOWN"
    })
    void createParseSupportsStructuredOfficeDocuments(String fileName, String fileExt,
                                                       String contentType, String expectedParseType) {
        RecordingParseRecordRepository records = new RecordingParseRecordRepository();
        FileObject file = activeFile(fileName, fileExt, contentType);
        FileParseApplicationService service = newService(new SingleFileObjectRepository(file),
                records, new NoopStorageAdapter());
        FileParseRequest request = new FileParseRequest();
        request.setProjectId(1L);

        var response = service.createParse(file.getId(), request);

        assertThat(response.getParseType()).isEqualTo(expectedParseType);
        assertThat(response.getResultFormat()).isEqualTo("MARKDOWN");
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void createParseReusesMatchingActiveTaskEvenWhenForceIsRequested() {
        FileObject file = activeFile("risk.xlsx", "xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        FileParseRecord active = new FileParseRecord();
        active.setId(702L);
        active.setProjectId(1L);
        active.setFileId(file.getId());
        active.setSourceFileHash(file.getFileHash());
        active.setResultFormat("MARKDOWN");
        active.setStatus("PARSING");
        active.setProgress(25);
        FileParseRecordRepository records = mock(FileParseRecordRepository.class);
        when(records.findActive(1L, file.getId(), "hash", "MARKDOWN"))
                .thenReturn(Optional.of(active));
        FileParseWorker worker = mock(FileParseWorker.class);
        FileParseApplicationService service = new FileParseApplicationService(
                new SingleFileObjectRepository(file), records, worker, new NoopStorageAdapter(),
                new FileProperties(), new ObjectMapper(),
                new ProjectAccessApplicationService(projectRepository(), new EmptyProjectMemberMapper()));
        FileParseRequest request = new FileParseRequest();
        request.setProjectId(1L);
        request.setForce(true);

        var response = service.createParse(file.getId(), request);

        assertThat(response.getRecordId()).isEqualTo(702L);
        assertThat(response.getStatus()).isEqualTo("PARSING");
        verify(records, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(worker, never()).parseAsync(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createParseReturnsWinningTaskWhenConcurrentInsertHitsUniqueConstraint() {
        FileObject file = activeFile("risk.xlsx", "xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        FileParseRecord winner = new FileParseRecord();
        winner.setId(703L);
        winner.setProjectId(1L);
        winner.setFileId(file.getId());
        winner.setSourceFileHash(file.getFileHash());
        winner.setResultFormat("MARKDOWN");
        winner.setStatus("PENDING");
        FileParseRecordRepository records = mock(FileParseRecordRepository.class);
        when(records.findActive(1L, file.getId(), "hash", "MARKDOWN"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DuplicateKeyException("duplicate active parse"))
                .when(records).insert(any(FileParseRecord.class));
        FileParseWorker worker = mock(FileParseWorker.class);
        FileParseApplicationService service = new FileParseApplicationService(
                new SingleFileObjectRepository(file), records, worker, new NoopStorageAdapter(),
                new FileProperties(), new ObjectMapper(),
                new ProjectAccessApplicationService(projectRepository(), new EmptyProjectMemberMapper()));
        FileParseRequest request = new FileParseRequest();
        request.setProjectId(1L);

        var response = service.createParse(file.getId(), request);

        assertThat(response.getRecordId()).isEqualTo(703L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(worker, never()).parseAsync(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PARSING", "RUNNING", "PARSED", "SUCCESS"})
    void retryParseRejectsRecordsThatAreNotFailedOrCanceled(String status) {
        FileParseRecord source = parseRecord(704L, status);
        FileParseApplicationService service = newService(
                new SingleFileObjectRepository(), new FixedParseRecordRepository(source), new NoopStorageAdapter());

        assertThatThrownBy(() -> service.retryParse(source.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getMessage()).contains("only failed or canceled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELED"})
    void retryParseCreatesNewTaskForRetryableRecords(String status) {
        FileObject file = activeFile("manual.pdf", "pdf", "application/pdf");
        FileParseRecord source = parseRecord(705L, status);
        FileParseRecordRepository records = mock(FileParseRecordRepository.class);
        when(records.findById(705L)).thenReturn(Optional.of(source));
        when(records.findActive(1L, file.getId(), "hash", "MARKDOWN")).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            FileParseRecord inserted = invocation.getArgument(0);
            inserted.setId(706L);
            when(records.findById(706L)).thenReturn(Optional.of(inserted));
            return inserted;
        }).when(records).insert(any(FileParseRecord.class));
        FileParseWorker worker = mock(FileParseWorker.class);
        FileParseApplicationService service = new FileParseApplicationService(
                new SingleFileObjectRepository(file), records, worker, new NoopStorageAdapter(),
                new FileProperties(), new ObjectMapper(),
                new ProjectAccessApplicationService(projectRepository(), new EmptyProjectMemberMapper()));

        var response = service.retryParse(705L);

        assertThat(response.getRecordId()).isEqualTo(706L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(worker).parseAsync(706L);
    }

    @Test
    void parsedStatusCanReadStoredContent() {
        FileParseRecord parsed = new FileParseRecord();
        parsed.setId(701L);
        parsed.setProjectId(1L);
        parsed.setFileId(99L);
        parsed.setStatus("PARSED");
        parsed.setResultFormat("MARKDOWN");
        parsed.setResultObjectName("results/701.md");
        FileParseApplicationService service = newService(
                new SingleFileObjectRepository(activeFile("manual.pdf", "pdf", "application/pdf")),
                new FixedParseRecordRepository(parsed), new NoopStorageAdapter("# parsed"));

        assertThat(service.getParseContent(701L).getContent()).isEqualTo("# parsed");
    }

    private FileParseApplicationService newService(FileObjectRepository fileObjectRepository,
                                                   FileParseRecordRepository parseRecordRepository,
                                                   StorageAdapter storageAdapter) {
        return new FileParseApplicationService(
                fileObjectRepository,
                parseRecordRepository,
                mock(FileParseWorker.class),
                storageAdapter,
                new FileProperties(),
                new ObjectMapper(),
                new ProjectAccessApplicationService(projectRepository(), new EmptyProjectMemberMapper())
        );
    }

    private static FileParseRecord parseRecord(Long id, String status) {
        FileParseRecord record = new FileParseRecord();
        record.setId(id);
        record.setProjectId(1L);
        record.setFileId(99L);
        record.setSourceFileHash("hash");
        record.setResultFormat("MARKDOWN");
        record.setStatus(status);
        return record;
    }

    private static FileObject activeFile(String fileName, String fileExt, String contentType) {
        FileObject file = new FileObject();
        file.setId(99L);
        file.setProjectId(1L);
        file.setFileName(fileName);
        file.setFileExt(fileExt);
        file.setContentType(contentType);
        file.setFileHash("hash");
        file.setStatus(FileStatus.ACTIVE.name());
        return file;
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
        private final FileObject file;

        private SingleFileObjectRepository() {
            this(activeFile("manual.pdf", "pdf", "application/pdf"));
        }

        private SingleFileObjectRepository(FileObject file) {
            this.file = file;
        }

        @Override public FileObject insert(FileObject fileObject) { return fileObject; }
        @Override public List<FileObject> findPage(com.xd.smartworksite.file.dto.FileQueryRequest request) { return List.of(); }
        @Override public Optional<FileObject> findById(Long fileId) { return Optional.of(file); }
        @Override public int markDeleted(Long fileId, String status) { return 0; }
    }

    private static class MissingReadBackParseRecordRepository implements FileParseRecordRepository {
        @Override public FileParseRecord insert(FileParseRecord record) { record.setId(700L); return record; }
        @Override public Optional<FileParseRecord> findById(Long recordId) { return Optional.empty(); }
        @Override public List<FileParseRecord> findByFileId(Long projectId, Long fileId) { return List.of(); }
        @Override public Optional<FileParseRecord> findLatestByFileId(Long projectId, Long fileId) { return Optional.empty(); }
        @Override public Optional<FileParseRecord> findLatestSuccessfulByFileId(Long projectId, Long fileId) { return Optional.empty(); }
        @Override public Optional<FileParseRecord> findReusable(Long projectId, Long fileId, String sourceFileHash, String resultFormat) { return Optional.empty(); }
        @Override public Optional<FileParseRecord> findActive(Long projectId, Long fileId, String sourceFileHash, String resultFormat) { return Optional.empty(); }
        @Override public int updateRunning(Long recordId, String stage, int progress) { return 0; }
        @Override public int updateSucceeded(FileParseRecord record) { return 0; }
        @Override public int updateFailed(Long recordId, String stage, String errorMessage) { return 0; }
    }

    private static class RecordingParseRecordRepository extends MissingReadBackParseRecordRepository {
        private FileParseRecord record;

        @Override public FileParseRecord insert(FileParseRecord record) {
            record.setId(700L);
            this.record = record;
            return record;
        }

        @Override public Optional<FileParseRecord> findById(Long recordId) { return Optional.ofNullable(record); }
    }

    private static class FixedParseRecordRepository extends MissingReadBackParseRecordRepository {
        private final FileParseRecord record;

        private FixedParseRecordRepository(FileParseRecord record) { this.record = record; }

        @Override public Optional<FileParseRecord> findById(Long recordId) { return Optional.of(record); }
    }

    private static class NoopStorageAdapter implements StorageAdapter {
        private final byte[] content;

        private NoopStorageAdapter() { this(""); }

        private NoopStorageAdapter(String content) { this.content = content.getBytes(StandardCharsets.UTF_8); }

        @Override public StorageObject upload(String objectName, InputStream inputStream, long size, String contentType) {
            return new StorageObject(objectName, "test-bucket", contentType, size);
        }
        @Override public InputStream openObject(String objectName) { return new ByteArrayInputStream(content); }
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
