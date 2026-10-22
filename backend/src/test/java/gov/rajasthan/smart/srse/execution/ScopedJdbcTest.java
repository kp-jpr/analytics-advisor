package gov.rajasthan.smart.srse.execution;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Statement;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.springframework.jdbc.core.JdbcTemplate;

class ScopedJdbcTest {

    @Test
    void copyKeepsTheDataSourceAndSetsItsOwnTimeout() {
        DataSource dataSource = mock(DataSource.class);
        JdbcTemplate shared = new JdbcTemplate(dataSource);
        shared.setQueryTimeout(180);

        JdbcTemplate scoped = ScopedJdbc.withTimeout(shared, 15);

        assertSame(dataSource, scoped.getDataSource());
        assertEquals(15, scoped.getQueryTimeout());
        assertEquals(180, shared.getQueryTimeout());
    }

    @Test
    void cancelAsksTheStatementToStop() throws Exception {
        Statement statement = mock(Statement.class);
        JdbcStatements.cancel(statement);
        verify(statement).cancel();
    }
}
