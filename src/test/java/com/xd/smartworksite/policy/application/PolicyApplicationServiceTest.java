package com.xd.smartworksite.policy.application;

import com.xd.smartworksite.ai.application.AiApplicationService;
import com.xd.smartworksite.policy.domain.PolicyArticle;
import com.xd.smartworksite.policy.domain.PolicySource;
import com.xd.smartworksite.policy.infra.PolicyCrawlerArticle;
import com.xd.smartworksite.policy.infra.PolicyCrawlerClient;
import com.xd.smartworksite.policy.repository.PolicyRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyApplicationServiceTest {

    @Test
    void recrawlKeepsExistingPublishDateWhenCrawlerDoesNotExtractOne() {
        PolicyArticle existing = existingArticle(LocalDate.of(2026, 1, 2));
        PolicyApplicationService service = serviceWith(existing);

        PolicyArticle updated = upsert(service, crawlerArticle(null));

        assertThat(updated.getPublishDate()).isEqualTo(LocalDate.of(2026, 1, 2));
    }

    @Test
    void recrawlUpdatesPublishDateWhenCrawlerExtractsOne() {
        PolicyArticle existing = existingArticle(LocalDate.of(2026, 1, 2));
        PolicyApplicationService service = serviceWith(existing);

        PolicyArticle updated = upsert(service, crawlerArticle(LocalDate.of(2026, 9, 18)));

        assertThat(updated.getPublishDate()).isEqualTo(LocalDate.of(2026, 9, 18));
    }

    private PolicyApplicationService serviceWith(PolicyArticle existing) {
        PolicyRepository repository = mock(PolicyRepository.class);
        when(repository.findArticleByProjectAndHash(eq(1L), anyString())).thenReturn(Optional.of(existing));
        when(repository.updateArticle(existing)).thenReturn(1);
        when(repository.findArticleById(existing.getId())).thenReturn(Optional.of(existing));
        return new PolicyApplicationService(
                repository,
                mock(ProjectAccessApplicationService.class),
                mock(TaskRepository.class),
                mock(TaskOutboxApplicationService.class),
                mock(PolicyCrawlerClient.class),
                mock(AiApplicationService.class),
                mock(PolicyKnowledgeBaseApplicationService.class)
        );
    }

    private PolicyArticle upsert(PolicyApplicationService service, PolicyCrawlerArticle item) {
        PolicySource source = new PolicySource();
        source.setId(2L);
        source.setProjectId(1L);
        source.setUrl("https://example.gov.cn/policy/article.html");
        return ReflectionTestUtils.invokeMethod(service, "upsertArticle", source, item);
    }

    private PolicyArticle existingArticle(LocalDate publishDate) {
        PolicyArticle article = new PolicyArticle();
        article.setId(3L);
        article.setProjectId(1L);
        article.setPublishDate(publishDate);
        return article;
    }

    private PolicyCrawlerArticle crawlerArticle(LocalDate publishDate) {
        PolicyCrawlerArticle article = new PolicyCrawlerArticle();
        article.setTitle("policy article");
        article.setUrl("https://example.gov.cn/policy/article.html");
        article.setSummary("summary");
        article.setContent("content");
        article.setPublishDate(publishDate);
        return article;
    }
}
