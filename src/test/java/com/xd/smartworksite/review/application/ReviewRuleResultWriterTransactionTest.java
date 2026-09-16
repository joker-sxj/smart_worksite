package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.review.domain.ReviewRuleResult;
import com.xd.smartworksite.review.repository.ReviewRuleResultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRuleResultWriterTransactionTest {

    @Test
    void rollsBackDeleteAndEarlierInsertsWhenOneRuleCannotBePersisted() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("insert into review_rule_result(review_record_id, project_id, rule_id, status, result_json) values (9, 1, 'RULE-OLD', 'COMPLETED', '{}')");
            ReviewRuleResultWriter writer = context.getBean(ReviewRuleResultWriter.class);
            var outcome = new ReviewRuleOrchestrator.ReviewOutcome(List.of(
                    rule("RULE-001"), rule("RULE-FAIL")));

            assertThatThrownBy(() -> writer.replace(9L, 1L, outcome)).isInstanceOf(RuntimeException.class);

            assertThat(jdbc.queryForList(
                    "select rule_id from review_rule_result where review_record_id = 9 order by id",
                    String.class)).containsExactly("RULE-OLD");
        }
    }

    private ReviewRuleOrchestrator.RuleResult rule(String ruleId) {
        return new ReviewRuleOrchestrator.RuleResult(ruleId, "COMPLETED",
                Map.of("confidence", 0.9, "issues", List.of()), false);
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:review_rule_writer;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                    "sa", "");
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("drop table if exists review_rule_result");
            jdbc.execute("""
                    create table review_rule_result (
                      id bigint primary key auto_increment,
                      review_record_id bigint not null,
                      project_id bigint not null,
                      rule_id varchar(64) not null check (rule_id <> 'RULE-FAIL'),
                      status varchar(32) not null,
                      result_json varchar(4000) not null,
                      confidence double,
                      manual_confirmation_required boolean,
                      error_message varchar(500)
                    )
                    """);
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ReviewRuleResultRepository repository(JdbcTemplate jdbc) {
            return new ReviewRuleResultRepository() {
                @Override
                public int deleteByReviewRecordId(Long reviewRecordId) {
                    return jdbc.update("delete from review_rule_result where review_record_id = ?", reviewRecordId);
                }

                @Override
                public int insert(ReviewRuleResult value) {
                    return jdbc.update("""
                                    insert into review_rule_result
                                      (review_record_id, project_id, rule_id, status, result_json, confidence,
                                       manual_confirmation_required, error_message)
                                    values (?, ?, ?, ?, ?, ?, ?, ?)
                                    """, value.getReviewRecordId(), value.getProjectId(), value.getRuleId(),
                            value.getStatus(), value.getResultJson(), value.getConfidence(),
                            value.getManualConfirmationRequired(), value.getErrorMessage());
                }

                @Override
                public List<ReviewRuleResult> findByReviewRecordId(Long reviewRecordId) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Bean
        ReviewRuleResultWriter writer(ReviewRuleResultRepository repository) {
            return new ReviewRuleResultWriter(repository, new ObjectMapper());
        }
    }
}
