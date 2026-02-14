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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * jOOQ Code GenerationによるJPA Entity生成のPoCテスト。
 * H2インメモリDBを使用して検証する。
 */
public class GspJpaEntityGeneratorTest {

    private static final String JDBC_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
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
            // テストテーブル1: 基本的なテーブル（PK、各種型）
            stmt.execute(
                "CREATE TABLE TEST_TBL1 ("
                + "  TEST_TBL1_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  TEST_NAME VARCHAR(30),"
                + "  TEST_VALUE INTEGER NOT NULL,"
                + "  VERSION_NO BIGINT"
                + ")"
            );

            // テストテーブル2: 外部キー
            stmt.execute(
                "CREATE TABLE TEST_TBL2 ("
                + "  TEST_TBL2_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  TEST_TBL1_ID BIGINT,"
                + "  TEST_DESCRIPTION VARCHAR(100),"
                + "  FOREIGN KEY (TEST_TBL1_ID) REFERENCES TEST_TBL1(TEST_TBL1_ID)"
                + ")"
            );

            // テストテーブル3: 各種データ型
            stmt.execute(
                "CREATE TABLE TYPETEST ("
                + "  TYPE_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  COL_VARCHAR VARCHAR(255),"
                + "  COL_INTEGER INTEGER,"
                + "  COL_BIGINT BIGINT,"
                + "  COL_SMALLINT SMALLINT,"
                + "  COL_BOOLEAN BOOLEAN,"
                + "  COL_DECIMAL DECIMAL(10,2),"
                + "  COL_DATE DATE,"
                + "  COL_TIMESTAMP TIMESTAMP,"
                + "  COL_BLOB BLOB"
                + ")"
            );
        }
    }

    @After
    public void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS TEST_TBL2");
                stmt.execute("DROP TABLE IF EXISTS TEST_TBL1");
                stmt.execute("DROP TABLE IF EXISTS TYPETEST");
            }
            connection.close();
        }
    }

    @Test
    public void testBasicH2EntityGeneration() throws Exception {
        File outputDir = tempDir.newFolder("generated");

        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        GenerationTool.generate(config);

        // 生成されたファイルの存在確認
        File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
        assertTrue("Entity出力ディレクトリが存在する", entityDir.exists());

        File testTbl1 = new File(entityDir, "TestTbl1.java");
        File testTbl2 = new File(entityDir, "TestTbl2.java");
        File typetest = new File(entityDir, "Typetest.java");

        assertTrue("TestTbl1.java が生成される", testTbl1.exists());
        assertTrue("TestTbl2.java が生成される", testTbl2.exists());
        assertTrue("Typetest.java が生成される", typetest.exists());

        // TestTbl1の内容検証
        String tbl1Content = Files.readString(testTbl1.toPath());
        assertTrue("パッケージ宣言がある", tbl1Content.contains("package jp.co.tis.gsptest.entity;"));
        assertTrue("@Generated(\"GSP\")がある", tbl1Content.contains("@Generated(\"GSP\")"));
        assertTrue("@Entityがある", tbl1Content.contains("@Entity"));
        assertTrue("@Tableがある", tbl1Content.contains("@Table(name = \"TEST_TBL1\")"));
        assertTrue("@Idがある", tbl1Content.contains("@Id"));
        assertTrue("@Columnがある", tbl1Content.contains("@Column("));
        assertTrue("Serializable実装", tbl1Content.contains("implements Serializable"));
        assertTrue("serialVersionUID", tbl1Content.contains("serialVersionUID"));
        assertTrue("publicフィールド", tbl1Content.contains("public Long testTbl1Id;")
                                     || tbl1Content.contains("public long testTbl1Id;"));
    }

    @Test
    public void testIgnoreTableNamePattern() throws Exception {
        File outputDir = tempDir.newFolder("generated_ignore");

        // TYPETEST を無視パターンに追加
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa",
            "(SCHEMA_INFO|.*\\$.*|TYPETEST)", false
        );

        GenerationTool.generate(config);

        File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
        File typetest = new File(entityDir, "Typetest.java");
        assertFalse("TYPETEST は無視パターンにより生成されない", typetest.exists());

        File testTbl1 = new File(entityDir, "TestTbl1.java");
        assertTrue("TEST_TBL1 は生成される", testTbl1.exists());
    }

    @Test
    public void testDataTypeMapping() throws Exception {
        File outputDir = tempDir.newFolder("generated_types");

        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        GenerationTool.generate(config);

        File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
        File typetest = new File(entityDir, "Typetest.java");
        String content = Files.readString(typetest.toPath());

        // 各型のマッピング検証
        assertTrue("VARCHAR → String", content.contains("String colVarchar"));
        assertTrue("INTEGER → Integer", content.contains("Integer colInteger"));
        assertTrue("BIGINT → Long", content.contains("Long colBigint")
                                    || content.contains("Long typeId"));
        assertTrue("SMALLINT → Short", content.contains("Short colSmallint"));
        assertTrue("BOOLEAN → boolean", content.contains("boolean colBoolean"));
        assertTrue("DECIMAL → BigDecimal", content.contains("BigDecimal colDecimal"));
        assertTrue("DATE → Date", content.contains("Date colDate"));
        assertTrue("TIMESTAMP → Timestamp", content.contains("Timestamp colTimestamp"));
    }
}
