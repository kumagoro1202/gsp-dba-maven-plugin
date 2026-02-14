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
 * {@link GspDomaEntityGenerator}によるDoma Entity生成のテスト。
 * H2インメモリDBを使用して検証する。
 */
public class GspDomaEntityGeneratorTest {

    private static final String JDBC_URL = "jdbc:h2:mem:domatestdb;DB_CLOSE_DELAY=-1";
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
            stmt.execute(
                "CREATE TABLE DOMA_TEST ("
                + "  DOMA_TEST_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  TEST_NAME VARCHAR(100),"
                + "  TEST_VALUE INTEGER NOT NULL,"
                + "  VERSION_NO BIGINT"
                + ")"
            );

            stmt.execute(
                "CREATE TABLE TYPE_CHECK ("
                + "  TYPE_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  COL_VARCHAR VARCHAR(255),"
                + "  COL_INTEGER INTEGER,"
                + "  COL_DECIMAL DECIMAL(10,2),"
                + "  COL_DATE DATE,"
                + "  COL_TIMESTAMP TIMESTAMP,"
                + "  COL_BOOLEAN BOOLEAN"
                + ")"
            );
        }
    }

    @After
    public void tearDown() throws Exception {
        GspEntityGenerationConfig.clearParams();
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS TYPE_CHECK");
                stmt.execute("DROP TABLE IF EXISTS DOMA_TEST");
            }
            connection.close();
        }
    }

    @Test
    public void testBasicDomaEntityGeneration() throws Exception {
        File outputDir = tempDir.newFolder("generated_doma");

        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "doma",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File domaTest = new File(entityDir, "DomaTest.java");
            assertTrue("DomaTest.java が生成される", domaTest.exists());

            String content = Files.readString(domaTest.toPath());

            // Domaアノテーション検証
            assertTrue("パッケージ宣言がある", content.contains("package jp.co.tis.gsptest.entity;"));
            assertTrue("@Generated(\"GSP\")がある", content.contains("@Generated(\"GSP\")"));
            assertTrue("@Entityがある（Doma）", content.contains("@Entity"));
            assertTrue("@Tableがある", content.contains("@Table(name = \"DOMA_TEST\")"));
            assertTrue("@Idがある", content.contains("@Id"));
            assertTrue("@Column(name = ...)がある", content.contains("@Column(name = \"DOMA_TEST_ID\")"));
            assertTrue("Serializable実装", content.contains("implements Serializable"));

            // Domaインポート検証
            assertTrue("org.seasar.doma.Entityインポート", content.contains("import org.seasar.doma.Entity;"));
            assertTrue("org.seasar.doma.Tableインポート", content.contains("import org.seasar.doma.Table;"));
            assertTrue("org.seasar.doma.Columnインポート", content.contains("import org.seasar.doma.Column;"));
            assertTrue("org.seasar.doma.Idインポート", content.contains("import org.seasar.doma.Id;"));

            // JPAアノテーションが含まれないことを検証
            assertFalse("jakarta.persistence不使用", content.contains("jakarta.persistence"));

            // Doma @SequenceGenerator形式の検証
            assertTrue("@GeneratedValue(strategy = GenerationType.SEQUENCE)",
                content.contains("@GeneratedValue(strategy = GenerationType.SEQUENCE)"));
            assertTrue("@SequenceGenerator(sequence = ...)",
                content.contains("@SequenceGenerator(sequence = "));

            // getter/setterが常に生成される
            assertTrue("getterが生成される", content.contains("public Long getDomaTestId()"));
            assertTrue("setterが生成される", content.contains("public void setDomaTestId("));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testDomaEntityWithPublicFields() throws Exception {
        File outputDir = tempDir.newFolder("generated_doma_public");

        // useAccessor=false（デフォルト） → publicフィールド + getter/setter
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "doma",
            "(SCHEMA_INFO|.*\\$.*)", false,
            false, 1, null
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File domaTest = new File(entityDir, "DomaTest.java");
            String content = Files.readString(domaTest.toPath());

            // useAccessor=false: publicフィールド
            assertTrue("publicフィールド", content.contains("public Long domaTestId;"));
            // Doma規約: getter/setterは常に生成
            assertTrue("getter生成（Doma規約）", content.contains("getDomaTestId()"));
            assertTrue("setter生成（Doma規約）", content.contains("setDomaTestId("));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testDomaEntityWithPrivateFields() throws Exception {
        File outputDir = tempDir.newFolder("generated_doma_private");

        // useAccessor=true → privateフィールド + getter/setter
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "doma",
            "(SCHEMA_INFO|.*\\$.*)", false,
            true, 1, null
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File domaTest = new File(entityDir, "DomaTest.java");
            String content = Files.readString(domaTest.toPath());

            assertTrue("privateフィールド", content.contains("private Long domaTestId;"));
            assertTrue("getter生成", content.contains("getDomaTestId()"));
            assertTrue("setter生成", content.contains("setDomaTestId("));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testDomaEntityColumnAnnotation() throws Exception {
        File outputDir = tempDir.newFolder("generated_doma_col");

        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "doma",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File domaTest = new File(entityDir, "DomaTest.java");
            String content = Files.readString(domaTest.toPath());

            // Doma @Columnはname属性のみ（length, precision, nullable, unique がない）
            assertTrue("@Column(name = \"TEST_NAME\")", content.contains("@Column(name = \"TEST_NAME\")"));
            assertFalse("length属性なし", content.contains("length = "));
            assertFalse("precision属性なし", content.contains("precision = "));
            assertFalse("nullable属性なし", content.contains("nullable = "));
            assertFalse("unique属性なし", content.contains("unique = "));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testDomaEntityVersionColumn() throws Exception {
        File outputDir = tempDir.newFolder("generated_doma_ver");

        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "doma",
            "(SCHEMA_INFO|.*\\$.*)", false,
            false, 1, "VERSION_NO"
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File domaTest = new File(entityDir, "DomaTest.java");
            String content = Files.readString(domaTest.toPath());

            assertTrue("@Versionインポート", content.contains("import org.seasar.doma.Version;"));
            assertTrue("@Versionアノテーション", content.contains("@Version"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testDomaEntityTypeMapping() throws Exception {
        File outputDir = tempDir.newFolder("generated_doma_types");

        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "doma",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File typeCheck = new File(entityDir, "TypeCheck.java");
            assertTrue("TypeCheck.java が生成される", typeCheck.exists());

            String content = Files.readString(typeCheck.toPath());

            assertTrue("VARCHAR → String", content.contains("String colVarchar"));
            assertTrue("INTEGER → Integer", content.contains("Integer colInteger"));
            assertTrue("DECIMAL → BigDecimal", content.contains("BigDecimal colDecimal"));
            assertTrue("DATE → Date", content.contains("Date colDate"));
            assertTrue("TIMESTAMP → Timestamp", content.contains("Timestamp colTimestamp"));
            assertTrue("BOOLEAN → boolean", content.contains("boolean colBoolean"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testEntityTypeSwitchJpaDefault() throws Exception {
        File outputDir = tempDir.newFolder("generated_jpa_switch");

        // entityType="jpa" → JPA Entity
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File domaTest = new File(entityDir, "DomaTest.java");
            String content = Files.readString(domaTest.toPath());

            // JPA Entity確認
            assertTrue("JPA: jakarta.persistence.Entity",
                content.contains("import jakarta.persistence.Entity;"));
            assertFalse("Doma: org.seasar.domaなし",
                content.contains("org.seasar.doma"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }
}
