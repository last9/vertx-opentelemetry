package io.last9.tracing.otel.v3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSpanNameTest {

    // ---- fromSql(sql) without dbName ----

    @Test
    void selectFromTable() {
        assertThat(SqlSpanName.fromSql("SELECT * FROM users WHERE id = ?"))
                .isEqualTo("SELECT users");
    }

    @Test
    void selectWithJoin() {
        assertThat(SqlSpanName.fromSql("SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id"))
                .isEqualTo("SELECT users");
    }

    @Test
    void insertIntoTable() {
        assertThat(SqlSpanName.fromSql("INSERT INTO orders (id, user_id) VALUES (?, ?)"))
                .isEqualTo("INSERT orders");
    }

    @Test
    void updateTable() {
        assertThat(SqlSpanName.fromSql("UPDATE orders SET status = 'shipped' WHERE id = ?"))
                .isEqualTo("UPDATE orders");
    }

    @Test
    void deleteFromTable() {
        assertThat(SqlSpanName.fromSql("DELETE FROM sessions WHERE expired = true"))
                .isEqualTo("DELETE sessions");
    }

    @Test
    void createTable() {
        assertThat(SqlSpanName.fromSql("CREATE TABLE users (id INT PRIMARY KEY)"))
                .isEqualTo("CREATE users");
    }

    @Test
    void dropTable() {
        assertThat(SqlSpanName.fromSql("DROP TABLE temp_data"))
                .isEqualTo("DROP temp_data");
    }

    @Test
    void showCommand() {
        assertThat(SqlSpanName.fromSql("SHOW VARIABLES")).isEqualTo("SHOW");
    }

    @Test
    void selectOneNoTable() {
        assertThat(SqlSpanName.fromSql("SELECT 1 AS alive")).isEqualTo("SELECT");
    }

    @Test
    void nullSql() {
        assertThat(SqlSpanName.fromSql(null)).isEqualTo("SQL");
    }

    @Test
    void emptySql() {
        assertThat(SqlSpanName.fromSql("")).isEqualTo("SQL");
    }

    @Test
    void blankSql() {
        assertThat(SqlSpanName.fromSql("   ")).isEqualTo("SQL");
    }

    // ---- fromSql(sql, dbName) with dbName qualification ----

    @Test
    void selectWithDbName() {
        assertThat(SqlSpanName.fromSql("SELECT * FROM users WHERE id = ?", "mydb"))
                .isEqualTo("SELECT mydb.users");
    }

    @Test
    void insertWithDbName() {
        assertThat(SqlSpanName.fromSql("INSERT INTO orders (id) VALUES (?)", "holdingdb"))
                .isEqualTo("INSERT holdingdb.orders");
    }

    @Test
    void updateWithDbName() {
        assertThat(SqlSpanName.fromSql("UPDATE holdings SET quantity = ? WHERE id = ?", "holdingdb"))
                .isEqualTo("UPDATE holdingdb.holdings");
    }

    @Test
    void deleteWithDbName() {
        assertThat(SqlSpanName.fromSql("DELETE FROM sessions WHERE expired = true", "appdb"))
                .isEqualTo("DELETE appdb.sessions");
    }

    @Test
    void showWithDbNameStillJustOperation() {
        // SHOW has no table, so dbName doesn't apply
        assertThat(SqlSpanName.fromSql("SHOW TABLES", "mydb")).isEqualTo("SHOW");
    }

    @Test
    void selectWithNullDbName() {
        assertThat(SqlSpanName.fromSql("SELECT * FROM users", null))
                .isEqualTo("SELECT users");
    }

    @Test
    void selectWithEmptyDbName() {
        assertThat(SqlSpanName.fromSql("SELECT * FROM users", ""))
                .isEqualTo("SELECT users");
    }

    @Test
    void alreadyQualifiedTableNotDoubleQualified() {
        // Table already has schema prefix — should NOT add dbName again
        assertThat(SqlSpanName.fromSql("SELECT * FROM myschema.users", "mydb"))
                .isEqualTo("SELECT myschema.users");
    }

    @Test
    void backtickQuotedTable() {
        assertThat(SqlSpanName.fromSql("SELECT * FROM `user-data` WHERE id = ?", "appdb"))
                .isEqualTo("SELECT appdb.user-data");
    }

    @Test
    void replaceIntoWithDbName() {
        assertThat(SqlSpanName.fromSql("REPLACE INTO cache (k, v) VALUES (?, ?)", "cachedb"))
                .isEqualTo("REPLACE cachedb.cache");
    }

    // ---- looksLikeSql ----

    @Test
    void looksLikeSqlForValidStatements() {
        assertThat(SqlSpanName.looksLikeSql("SELECT 1")).isTrue();
        assertThat(SqlSpanName.looksLikeSql("INSERT INTO foo VALUES (1)")).isTrue();
        assertThat(SqlSpanName.looksLikeSql("update bar set x = 1")).isTrue();
    }

    @Test
    void looksLikeSqlFalseForNonSql() {
        assertThat(SqlSpanName.looksLikeSql("GET /api/users")).isFalse();
        assertThat(SqlSpanName.looksLikeSql("hello world")).isFalse();
        assertThat(SqlSpanName.looksLikeSql(null)).isFalse();
        assertThat(SqlSpanName.looksLikeSql("")).isFalse();
    }
}
