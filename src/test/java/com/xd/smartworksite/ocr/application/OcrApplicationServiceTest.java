package com.xd.smartworksite.ocr.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.ocr.domain.OcrRecord;
import com.xd.smartworksite.ocr.dto.OcrRecordResponse;
import com.xd.smartworksite.ocr.repository.OcrRepository;
import com.xd.smartworksite.project.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OcrApplicationServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void masksSensitiveFieldsAndRawResultForViewOnlyUser() {
        Fixture fixture = fixture(List.of("PROJECT_USER"), List.of("ocr:view"));
        OcrRecord record = recordWithIdNumber();
        when(fixture.repository.findRecordById(1L)).thenReturn(Optional.of(record));
        ProjectMember member = new ProjectMember();
        member.setStatus("ENABLED");
        when(fixture.memberMapper.selectByProjectIdAndUserId(10L, 7L)).thenReturn(member);

        OcrRecordResponse response = fixture.service.get(1L);

        assertThat(response.getFields().get(0).getFieldValue()).isEqualTo("370202********1234");
        assertThat(response.getFields().get(0).getManualConfirmationRequired()).isFalse();
        assertThat(response.getRawResult().toString()).doesNotContain("370202199001011234");
    }

    @Test
    void exposesSensitiveFieldsToOcrManager() {
        Fixture fixture = fixture(List.of("PLATFORM_ADMIN"), List.of("ocr:view", "ocr:manage"));
        OcrRecord record = recordWithIdNumber();
        when(fixture.repository.findRecordById(1L)).thenReturn(Optional.of(record));
        ProjectMember member = new ProjectMember();
        member.setStatus("ENABLED");
        when(fixture.memberMapper.selectByProjectIdAndUserId(10L, 7L)).thenReturn(member);

        OcrRecordResponse response = fixture.service.get(1L);

        assertThat(response.getFields().get(0).getFieldValue()).isEqualTo("370202199001011234");
    }

    @Test
    void mapsExplainabilityFieldsAndMasksSensitiveCandidatesForViewOnlyUser() {
        Fixture fixture = fixture(List.of("PROJECT_USER"), List.of("ocr:view"));
        OcrRecord record = recordWithIdNumber();
        record.setFieldsJson("{\"fields\":[{\"fieldKey\":\"idNumber\",\"fieldName\":\"身份证号\",\"fieldValue\":\"\",\"confidence\":0.4,\"manualConfirmationRequired\":true,\"confirmationReason\":\"DUAL_PASS_CONFLICT\",\"candidates\":[{\"value\":\"370202199001011234\",\"confidence\":0.91,\"evidence\":\"身份证号 370202199001011234\",\"source\":\"ORIGINAL\"}]}]}");
        when(fixture.repository.findRecordById(1L)).thenReturn(Optional.of(record));
        ProjectMember member = new ProjectMember(); member.setStatus("ENABLED");
        when(fixture.memberMapper.selectByProjectIdAndUserId(10L, 7L)).thenReturn(member);

        OcrRecordResponse response = fixture.service.get(1L);

        assertThat(response.getFields().get(0).getConfirmationReason()).isEqualTo("DUAL_PASS_CONFLICT");
        assertThat(response.getFields().get(0).getCandidates()).isEmpty();
    }

    @Test
    void keepsOldOcrRecordsReadableWithoutExplainabilityFields() {
        Fixture fixture = fixture(List.of("PLATFORM_ADMIN"), List.of("ocr:view", "ocr:manage"));
        OcrRecord record = recordWithIdNumber();
        when(fixture.repository.findRecordById(1L)).thenReturn(Optional.of(record));
        assertThat(fixture.service.get(1L).getFields().get(0).getConfirmationReason()).isNull();
        assertThat(fixture.service.get(1L).getFields().get(0).getCandidates()).isEmpty();
    }

    @Test
    void confirmsCompletedOcrRecordAndPersistsAuditIdentity() {
        Fixture fixture = fixture(List.of("PLATFORM_ADMIN"), List.of("ocr:view", "ocr:manage"));
        OcrRecord record = recordWithIdNumber();
        when(fixture.repository.findRecordById(1L)).thenReturn(Optional.of(record));
        OcrRecord confirmed = recordWithIdNumber();
        confirmed.setManuallyConfirmed(true);
        when(fixture.repository.findRecordById(1L)).thenReturn(Optional.of(record), Optional.of(confirmed));
        when(fixture.repository.confirmRecord(1L, 7L)).thenReturn(1);

        OcrRecordResponse response = fixture.service.confirm(1L);

        assertThat(response.getManuallyConfirmed()).isTrue();
        org.mockito.Mockito.verify(fixture.repository).confirmRecord(1L, 7L);
    }

    @Test
    void refusesConfirmationWhileRecognitionIsNotCompleted() {
        Fixture fixture = fixture(List.of("PLATFORM_ADMIN"), List.of("ocr:view", "ocr:manage"));
        OcrRecord record = recordWithIdNumber();
        record.setStatus("PROCESSING");
        when(fixture.repository.findRecordById(1L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> fixture.service.confirm(1L))
                .isInstanceOf(com.xd.smartworksite.common.exception.BusinessException.class)
                .hasMessageContaining("识别尚未完成");
        org.mockito.Mockito.verify(fixture.repository, org.mockito.Mockito.never()).confirmRecord(1L, 7L);
    }

    @Test
    void refusesConfirmationWhenAFieldStillRequiresManualReview() {
        Fixture fixture = fixture(List.of("PLATFORM_ADMIN"), List.of("ocr:view", "ocr:manage"));
        OcrRecord record = recordWithIdNumber();
        record.setFieldsJson("{\"fields\":[{\"fieldKey\":\"name\",\"fieldName\":\"姓名\",\"fieldValue\":\"\",\"manualConfirmationRequired\":true}]}");
        when(fixture.repository.findRecordById(1L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> fixture.service.confirm(1L))
                .isInstanceOf(com.xd.smartworksite.common.exception.BusinessException.class)
                .hasMessageContaining("仍有字段需要人工确认");
        org.mockito.Mockito.verify(fixture.repository, org.mockito.Mockito.never()).confirmRecord(1L, 7L);
    }

    @Test
    void exposesExplicitPassportPermitResidentCardAndContractTypes() {
        Fixture fixture = fixture(List.of("PLATFORM_ADMIN"), List.of("ocr:view", "ocr:manage"));

        assertThat(fixture.service.types()).extracting(type -> type.getOcrType())
                .contains("PASSPORT", "TRAVEL_PERMIT", "FIVE_STAR_CARD", "CONTRACT");
    }

    private Fixture fixture(List<String> roles, List<String> permissions) {
        UserPrincipal principal = new UserPrincipal(7L, "tester", roles, permissions, 10L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        OcrRepository repository = mock(OcrRepository.class);
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        OcrApplicationService service = new OcrApplicationService(
                repository,
                mock(FileObjectApplicationService.class),
                mock(ProjectRepository.class),
                memberMapper,
                mock(OcrRecognitionWorker.class),
                new ObjectMapper());
        return new Fixture(service, repository, memberMapper);
    }

    private OcrRecord recordWithIdNumber() {
        OcrRecord record = new OcrRecord();
        record.setId(1L);
        record.setProjectId(10L);
        record.setStatus("SUCCESS");
        record.setOcrType("ID_CARD");
        record.setFieldsJson("{\"fields\":[{\"fieldKey\":\"idNumber\",\"fieldName\":\"身份证号\",\"fieldValue\":\"370202199001011234\",\"confidence\":0.95}],\"raw\":{\"text\":\"身份证号370202199001011234\"}}");
        return record;
    }

    private record Fixture(OcrApplicationService service,
                           OcrRepository repository,
                           ProjectMemberMapper memberMapper) {}
}
