/*
 * Copyright (C) 2015 coastland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.co.tis.gsp.tools.dba.entitygen;

import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.Assert.*;

/**
 * {@link GspViewSupport}のテスト。
 * H2インメモリDBでVIEWを作成し、PK推定ロジックを検証する。
 */
public class GspViewSupportTest {

    private static final String JDBC_URL = "jdbc:h2:mem:viewtestdb;DB_CLOSE_DELAY=-1";
    private static final String JDBC_USER = "sa";
    private static final String JDBC_PASSWORD = "";
    private static final String JDBC_DRIVER = "org.h2.Driver";
    private static final String SCHEMA = "PUBLIC";

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private Connection connection;

    @Before
    public void setUp() throws Exception {
        Class.forName(JDBC_DRIVER);
        connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);

        try (Statement stmt = connection.createStatement()) {
            // ベーステーブル（単一PK）
            stmt.execute(
                "CREATE TABLE BASE_TABLE ("
                + "  BASE_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  COL_NAME VARCHAR(100),"
                + "  COL_VALUE INTEGER"
                + ")"
            );

            // 複合PKのベーステーブル
            stmt.execute(
                "CREATE TABLE COMPOSITE_PK_TABLE ("
                + "  PK1 BIGINT NOT NULL,"
                + "  PK2 BIGINT NOT NULL,"
                + "  COL_DATA VARCHAR(200),"
                + "  PRIMARY KEY (PK1, PK2)"
                + ")"
            );

            // JOIN用テーブル
            stmt.execute(
                "CREATE TABLE JOIN_TABLE ("
                + "  JOIN_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  BASE_ID BIGINT,"
                + "  JOIN_DATA VARCHAR(100)"
                + ")"
            );

            // 単純VIEW（PKが推定可能）
            stmt.execute(
                "CREATE VIEW SIMPLE_VIEW AS SELECT BASE_ID, COL_NAME FROM BASE_TABLE"
            );

            // 複合PK VIEW
            stmt.execute(
                "CREATE VIEW COMPOSITE_VIEW AS SELECT PK1, PK2, COL_DATA FROM COMPOSITE_PK_TABLE"
            );

            // JOIN VIEW（複雑→PK推定しない）
            stmt.execute(
                "CREATE VIEW JOIN_VIEW AS SELECT b.BASE_ID, b.COL_NAME, j.JOIN_DATA "
                + "FROM BASE_TABLE b JOIN JOIN_TABLE j ON b.BASE_ID = j.BASE_ID"
            );

            // PKカラムが欠けているVIEW
            stmt.execute(
                "CREATE VIEW PARTIAL_VIEW AS SELECT COL_NAME, COL_VALUE FROM BASE_TABLE"
            );
        }
    }

    @After
    public void tearDown() throws Exception {
        GspEntityGenerationConfig.clearParams();
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP VIEW IF EXISTS SIMPLE_VIEW");
                stmt.execute("DROP VIEW IF EXISTS COMPOSITE_VIEW");
                stmt.execute("DROP VIEW IF EXISTS JOIN_VIEW");
                stmt.execute("DROP VIEW IF EXISTS PARTIAL_VIEW");
                stmt.execute("DROP TABLE IF EXISTS JOIN_TABLE");
                stmt.execute("DROP TABLE IF EXISTS COMPOSITE_PK_TABLE");
                stmt.execute("DROP TABLE IF EXISTS BASE_TABLE");
            }
            connection.close();
        }
    }

    // ========== extractBaseTable() 単体テスト ==========

    @Test
    public void testExtractBaseTable_simpleSelect() {
        assertEquals("MY_TABLE", GspViewSupport.extractBaseTable("SELECT col1, col2 FROM MY_TABLE"));
    }

    @Test
    public void testExtractBaseTable_withSchemaPrefix() {
        assertEquals("MY_TABLE", GspViewSupport.extractBaseTable("SELECT col1 FROM \"PUBLIC\".\"MY_TABLE\""));
    }

    @Test
    public void testExtractBaseTable_withAlias() {
        String result = GspViewSupport.extractBaseTable("SELECT t.col1 FROM MY_TABLE t");
        // テーブル名が抽出される（エイリアスは無視）
        assertNotNull(result);
        assertEquals("MY_TABLE", result);
    }

    @Test
    public void testExtractBaseTable_joinReturnsNull() {
        assertNull(GspViewSupport.extractBaseTable(
            "SELECT a.col1 FROM TABLE_A a JOIN TABLE_B b ON a.id = b.id"));
    }

    @Test
    public void testExtractBaseTable_innerJoinReturnsNull() {
        assertNull(GspViewSupport.extractBaseTable(
            "SELECT a.col1 FROM TABLE_A a INNER JOIN TABLE_B b ON a.id = b.id"));
    }

    @Test
    public void testExtractBaseTable_leftJoinReturnsNull() {
        assertNull(GspViewSupport.extractBaseTable(
            "SELECT a.col1 FROM TABLE_A a LEFT JOIN TABLE_B b ON a.id = b.id"));
    }

    @Test
    public void testExtractBaseTable_groupByReturnsNull() {
        assertNull(GspViewSupport.extractBaseTable(
            "SELECT col1, COUNT(*) FROM MY_TABLE GROUP BY col1"));
    }

    @Test
    public void testExtractBaseTable_unionReturnsNull() {
        assertNull(GspViewSupport.extractBaseTable(
            "SELECT col1 FROM TABLE_A UNION SELECT col1 FROM TABLE_B"));
    }

    @Test
    public void testExtractBaseTable_nullInput() {
        assertNull(GspViewSupport.extractBaseTable(null));
    }

    @Test
    public void testExtractBaseTable_caseInsensitive() {
        String result = GspViewSupport.extractBaseTable("select col1 from my_table");
        assertNotNull(result);
        assertEquals("MY_TABLE", result);
    }

    // ========== analyzeViews() 統合テスト（H2） ==========

    @Test
    public void testAnalyzeViews_simpleView() {
        GspViewSupport.analyzeViews(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);
        try {
            assertTrue("SIMPLE_VIEWはVIEW", GspViewSupport.isView("SIMPLE_VIEW"));
            List<String> pks = GspViewSupport.getInferredPrimaryKeys("SIMPLE_VIEW");
            assertEquals("SIMPLE_VIEWの推定PK数", 1, pks.size());
            assertTrue("SIMPLE_VIEWの推定PK名はBASE_ID",
                pks.get(0).equalsIgnoreCase("BASE_ID"));
        } finally {
            GspViewSupport.clear();
        }
    }

    @Test
    public void testAnalyzeViews_compositeView() {
        GspViewSupport.analyzeViews(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);
        try {
            assertTrue("COMPOSITE_VIEWはVIEW", GspViewSupport.isView("COMPOSITE_VIEW"));
            List<String> pks = GspViewSupport.getInferredPrimaryKeys("COMPOSITE_VIEW");
            assertEquals("COMPOSITE_VIEWの推定PK数", 2, pks.size());
        } finally {
            GspViewSupport.clear();
        }
    }

    @Test
    public void testAnalyzeViews_joinViewHasNoPK() {
        GspViewSupport.analyzeViews(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);
        try {
            assertTrue("JOIN_VIEWはVIEW", GspViewSupport.isView("JOIN_VIEW"));
            List<String> pks = GspViewSupport.getInferredPrimaryKeys("JOIN_VIEW");
            assertTrue("JOIN_VIEWはPK推定しない", pks.isEmpty());
        } finally {
            GspViewSupport.clear();
        }
    }

    @Test
    public void testAnalyzeViews_partialViewHasNoPK() {
        GspViewSupport.analyzeViews(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);
        try {
            assertTrue("PARTIAL_VIEWはVIEW", GspViewSupport.isView("PARTIAL_VIEW"));
            List<String> pks = GspViewSupport.getInferredPrimaryKeys("PARTIAL_VIEW");
            assertTrue("PARTIAL_VIEWはPKカラムが欠けているのでPK推定しない", pks.isEmpty());
        } finally {
            GspViewSupport.clear();
        }
    }

    @Test
    public void testIsView_regularTableReturnsFalse() {
        GspViewSupport.analyzeViews(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);
        try {
            assertFalse("BASE_TABLEはVIEWではない", GspViewSupport.isView("BASE_TABLE"));
        } finally {
            GspViewSupport.clear();
        }
    }

    @Test
    public void testClear() {
        GspViewSupport.analyzeViews(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);
        assertTrue(GspViewSupport.isView("SIMPLE_VIEW"));

        GspViewSupport.clear();
        assertFalse("clear後はVIEW情報が消える", GspViewSupport.isView("SIMPLE_VIEW"));
    }

    @Test
    public void testIsView_caseInsensitive() {
        GspViewSupport.analyzeViews(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);
        try {
            assertTrue("小文字でもVIEW判定可能", GspViewSupport.isView("simple_view"));
        } finally {
            GspViewSupport.clear();
        }
    }

    @Test
    public void testGetInferredPrimaryKeys_beforeAnalyze() {
        // analyzeViews未実行時は空リストを返す
        List<String> pks = GspViewSupport.getInferredPrimaryKeys("SIMPLE_VIEW");
        assertTrue("未解析時は空リスト", pks.isEmpty());
    }

    // ========== Entity生成統合テスト ==========

    @Test
    public void testViewEntityHasIdButNoGeneratedValue() throws Exception {
        File outputDir = tempDir.newFolder("generated_view");

        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File simpleView = new File(entityDir, "SimpleView.java");
            assertTrue("SimpleView.java が生成される", simpleView.exists());

            String content = Files.readString(simpleView.toPath());
            assertTrue("@Idがある", content.contains("@Id"));
            assertFalse("@GeneratedValueがない（VIEWなので）", content.contains("@GeneratedValue"));
            assertFalse("@SequenceGeneratorがない", content.contains("@SequenceGenerator"));
            assertTrue("@Entityがある", content.contains("@Entity"));
            assertTrue("@Tableがある", content.contains("@Table(name = \"SIMPLE_VIEW\")"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testJoinViewEntityHasNoId() throws Exception {
        File outputDir = tempDir.newFolder("generated_joinview");

        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File joinView = new File(entityDir, "JoinView.java");
            assertTrue("JoinView.java が生成される", joinView.exists());

            String content = Files.readString(joinView.toPath());
            assertFalse("@Idがない（複雑VIEWなのでPK推定しない）", content.contains("@Id"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testRegularTableEntityUnaffectedByViewSupport() throws Exception {
        File outputDir = tempDir.newFolder("generated_table");

        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File baseTable = new File(entityDir, "BaseTable.java");
            assertTrue("BaseTable.java が生成される", baseTable.exists());

            String content = Files.readString(baseTable.toPath());
            assertTrue("通常テーブルは@Idがある", content.contains("@Id"));
            assertTrue("通常テーブルは@GeneratedValueがある", content.contains("@GeneratedValue"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }
}
