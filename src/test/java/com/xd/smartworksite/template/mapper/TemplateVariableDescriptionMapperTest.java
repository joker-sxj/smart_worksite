package com.xd.smartworksite.template.mapper;

import com.xd.smartworksite.template.domain.TemplateVariableDescription;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateVariableDescriptionMapperTest {

    private TemplateVariableDescriptionMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:template_variable_mapper;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS template_variable_description");
            statement.execute("""
                    CREATE TABLE template_variable_description (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      project_id BIGINT NOT NULL,
                      template_id BIGINT NOT NULL,
                      file_id BIGINT NOT NULL,
                      variable_name VARCHAR(128) NOT NULL,
                      description VARCHAR(2000) NOT NULL,
                      data_source_ids VARCHAR(2000),
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      created_by BIGINT,
                      updated_by BIGINT,
                      deleted TINYINT NOT NULL DEFAULT 0,
                      UNIQUE (template_id, file_id, variable_name)
                    )
                    """);
        }

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/template/TemplateVariableDescriptionMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        mapper = sqlSessionFactory.openSession(true).getMapper(TemplateVariableDescriptionMapper.class);
    }

    @Test
    void insertUpdateAndReadbackUseFileScopedVariableKey() {
        TemplateVariableDescription record = new TemplateVariableDescription();
        record.setProjectId(1L);
        record.setTemplateId(10L);
        record.setFileId(20L);
        record.setVariableName("var_project_name");
        record.setDescription("项目名称");
        record.setDataSourceIds("[20,21]");
        record.setCreatedBy(7L);
        record.setUpdatedBy(7L);

        assertThat(mapper.insert(record)).isEqualTo(1);
        assertThat(record.getId()).isNotNull();
        assertThat(mapper.selectByKey(10L, 20L, "var_project_name"))
                .satisfies(saved -> {
                    assertThat(saved.getDescription()).isEqualTo("项目名称");
                    assertThat(saved.getDataSourceIds()).isEqualTo("[20,21]");
                });

        record.setDescription("项目正式名称");
        assertThat(mapper.updateAndReactivate(record)).isEqualTo(1);

        assertThat(mapper.selectActiveByTemplateAndFile(10L, 20L))
                .singleElement()
                .satisfies(saved -> assertThat(saved.getDescription()).isEqualTo("项目正式名称"));
    }
}
