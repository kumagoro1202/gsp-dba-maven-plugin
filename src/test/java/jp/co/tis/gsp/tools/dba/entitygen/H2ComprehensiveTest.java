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
 * H2インメモリDBを使用した包括的テスト（Phase 3-E）。
 * 各種テーブル構造・制約・型に対してJPA/Domaの両方でEntity生成を検証する。
 */
public class H2ComprehensiveTest {

    private static final String JDBC_URL = "jdbc:h2:mem:comprehensivedb;DB_CLOSE_DELAY=-1";
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
    }

    @After
    public void tearDown() throws Exception {
        GspEntityGenerationConfig.clearParams();
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
    }

    @Test
    public void testCompositePrimaryKey() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE COMPOSITE_PK ("
                + "  KEY1 BIGINT NOT NULL,"
                + "  KEY2 VARCHAR(50) NOT NULL,"
                + "  VAL VARCHAR(100),"
                + "  PRIMARY KEY (KEY1, KEY2))");
        }

        File outputDir = tempDir.newFolder("composite_pk");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File entity = new File(entityDir, "CompositePk.java");
            assertTrue("CompositePk.java が生成される", entity.exists());

            String content = Files.readString(entity.toPath());
            // 複合PKの場合、@Idは2つ
            int idCount = countOccurrences(content, "@Id");
            assertTrue("@Idが2つ以上", idCount >= 2);
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testCompositeForeignKey() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE COMP_PARENT ("
                + "  PK1 BIGINT NOT NULL, PK2 BIGINT NOT NULL,"
                + "  PRIMARY KEY (PK1, PK2))");
            stmt.execute("CREATE TABLE COMP_CHILD ("
                + "  ID BIGINT NOT NULL PRIMARY KEY,"
                + "  FK1 BIGINT, FK2 BIGINT,"
                + "  FOREIGN KEY (FK1, FK2) REFERENCES COMP_PARENT(PK1, PK2))");
        }

        File outputDir = tempDir.newFolder("composite_fk");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            String childContent = Files.readString(new File(entityDir, "CompChild.java").toPath());

            assertTrue("@JoinColumnsインポート", childContent.contains("import jakarta.persistence.JoinColumns;"));
            assertTrue("@JoinColumnsアノテーション", childContent.contains("@JoinColumns({"));
            assertTrue("FK1のJoinColumn", childContent.contains("name = \"FK1\""));
            assertTrue("FK2のJoinColumn", childContent.contains("name = \"FK2\""));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testSelfReferentialFK() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE CATEGORY ("
                + "  CATEGORY_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  PARENT_CATEGORY_ID BIGINT,"
                + "  NAME VARCHAR(100),"
                + "  FOREIGN KEY (PARENT_CATEGORY_ID) REFERENCES CATEGORY(CATEGORY_ID))");
        }

        File outputDir = tempDir.newFolder("self_ref");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            String content = Files.readString(new File(entityDir, "Category.java").toPath());

            assertTrue("@ManyToOne（自己参照）", content.contains("@ManyToOne"));
            assertTrue("@OneToMany（自己参照）", content.contains("@OneToMany"));
            assertTrue("自己参照型フィールド", content.contains("Category category;"));
            assertTrue("自己参照Listフィールド", content.contains("java.util.List<Category>"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testVersionColumnPattern() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE VERSIONED ("
                + "  ID BIGINT NOT NULL PRIMARY KEY,"
                + "  NAME VARCHAR(50),"
                + "  VERSION_NO BIGINT,"
                + "  UPD_VERSION INTEGER)");
        }

        File outputDir = tempDir.newFolder("version");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false,
            false, 1, "VERSION_NO|UPD_VERSION"
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            String content = Files.readString(new File(entityDir, "Versioned.java").toPath());

            assertTrue("@Versionインポート", content.contains("import jakarta.persistence.Version;"));
            int versionCount = countOccurrences(content, "@Version");
            assertTrue("@Versionが2つ", versionCount == 2);
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testUseAccessorTrue() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE ACCESSOR_TEST ("
                + "  ID BIGINT NOT NULL PRIMARY KEY,"
                + "  NAME VARCHAR(50))");
        }

        File outputDir = tempDir.newFolder("accessor");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false,
            true, 1, null
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            String content = Files.readString(new File(entityDir, "AccessorTest.java").toPath());

            assertTrue("privateフィールド", content.contains("private Long id;")
                || content.contains("private long id;"));
            assertTrue("getter", content.contains("getId()"));
            assertTrue("setter", content.contains("setId("));
            assertFalse("publicフィールドなし", content.contains("public Long id;")
                || content.contains("public long id;"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testAllocationSizeParameter() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE ALLOC_TEST ("
                + "  ID BIGINT NOT NULL PRIMARY KEY,"
                + "  NAME VARCHAR(50))");
        }

        File outputDir = tempDir.newFolder("alloc");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false,
            false, 50, null
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            String content = Files.readString(new File(entityDir, "AllocTest.java").toPath());

            assertTrue("allocationSize = 50", content.contains("allocationSize = 50"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testJSR310TypeMapping() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE JSR310_TEST ("
                + "  ID BIGINT NOT NULL PRIMARY KEY,"
                + "  COL_DATE DATE,"
                + "  COL_TIMESTAMP TIMESTAMP)");
        }

        File outputDir = tempDir.newFolder("jsr310");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", true
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            String content = Files.readString(new File(entityDir, "Jsr310Test.java").toPath());

            assertTrue("LocalDateインポート", content.contains("import java.time.LocalDate;"));
            assertTrue("LocalDateTimeインポート", content.contains("import java.time.LocalDateTime;"));
            assertTrue("LocalDateフィールド", content.contains("LocalDate colDate"));
            assertTrue("LocalDateTimeフィールド", content.contains("LocalDateTime colTimestamp"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testDomaWithFK() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE DOMA_PARENT ("
                + "  PARENT_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  NAME VARCHAR(50))");
            stmt.execute("CREATE TABLE DOMA_CHILD ("
                + "  CHILD_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  PARENT_ID BIGINT,"
                + "  FOREIGN KEY (PARENT_ID) REFERENCES DOMA_PARENT(PARENT_ID))");
        }

        File outputDir = tempDir.newFolder("doma_fk");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "doma", "", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            File domaChild = new File(entityDir, "DomaChild.java");
            assertTrue("DomaChild.java が生成される", domaChild.exists());

            String content = Files.readString(domaChild.toPath());

            // DomaはFK関連アノテーションを使わない
            assertFalse("Doma: @ManyToOneなし", content.contains("@ManyToOne"));
            assertFalse("Doma: @JoinColumnなし", content.contains("@JoinColumn"));

            // Domaアノテーションは存在する
            assertTrue("Doma: @Entity", content.contains("@Entity"));
            assertTrue("Doma: @Table", content.contains("@Table(name = \"DOMA_CHILD\")"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testMultipleUniqueConstraints() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE MULTI_UK ("
                + "  ID BIGINT NOT NULL PRIMARY KEY,"
                + "  CODE1 VARCHAR(50) NOT NULL,"
                + "  CODE2 VARCHAR(50) NOT NULL,"
                + "  EMAIL VARCHAR(255) NOT NULL,"
                + "  CONSTRAINT UK_CODE1_CODE2 UNIQUE (CODE1, CODE2),"
                + "  CONSTRAINT UK_EMAIL UNIQUE (EMAIL))");
        }

        File outputDir = tempDir.newFolder("multi_uk");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            String content = Files.readString(new File(entityDir, "MultiUk.java").toPath());

            assertTrue("@UniqueConstraintインポート",
                content.contains("import jakarta.persistence.UniqueConstraint;"));
            int ukCount = countOccurrences(content, "@UniqueConstraint(");
            assertTrue("@UniqueConstraintが2つ", ukCount >= 2);
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testNullableAndNotNullColumns() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE NULLABLE_TEST ("
                + "  ID BIGINT NOT NULL PRIMARY KEY,"
                + "  REQUIRED_COL VARCHAR(50) NOT NULL,"
                + "  OPTIONAL_COL VARCHAR(50))");
        }

        File outputDir = tempDir.newFolder("nullable");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            String content = Files.readString(new File(entityDir, "NullableTest.java").toPath());

            assertTrue("NOT NULLカラム: nullable = false",
                content.contains("name = \"REQUIRED_COL\"") && content.contains("nullable = false"));
            assertTrue("NULLABLEカラム: nullable = true",
                content.contains("name = \"OPTIONAL_COL\"") && content.contains("nullable = true"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testThreeTableChainFK() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE GRANDPARENT ("
                + "  GP_ID BIGINT NOT NULL PRIMARY KEY, NAME VARCHAR(50))");
            stmt.execute("CREATE TABLE PARENT_TBL ("
                + "  P_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  GP_ID BIGINT,"
                + "  FOREIGN KEY (GP_ID) REFERENCES GRANDPARENT(GP_ID))");
            stmt.execute("CREATE TABLE CHILD_TBL ("
                + "  C_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  P_ID BIGINT,"
                + "  FOREIGN KEY (P_ID) REFERENCES PARENT_TBL(P_ID))");
        }

        File outputDir = tempDir.newFolder("chain_fk");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");

            // GRANDPARENT: @OneToMany → PARENT_TBL
            String gpContent = Files.readString(new File(entityDir, "Grandparent.java").toPath());
            assertTrue("Grandparent: @OneToMany", gpContent.contains("@OneToMany"));
            assertTrue("Grandparent: List<ParentTbl>", gpContent.contains("java.util.List<ParentTbl>"));

            // PARENT_TBL: @ManyToOne → GRANDPARENT, @OneToMany → CHILD_TBL
            String pContent = Files.readString(new File(entityDir, "ParentTbl.java").toPath());
            assertTrue("ParentTbl: @ManyToOne", pContent.contains("@ManyToOne"));
            assertTrue("ParentTbl: @OneToMany", pContent.contains("@OneToMany"));

            // CHILD_TBL: @ManyToOne → PARENT_TBL
            String cContent = Files.readString(new File(entityDir, "ChildTbl.java").toPath());
            assertTrue("ChildTbl: @ManyToOne", cContent.contains("@ManyToOne"));
            assertFalse("ChildTbl: @OneToManyなし", cContent.contains("@OneToMany"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    @Test
    public void testIdentityColumn() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IDENTITY_TEST ("
                + "  ID BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                + "  NAME VARCHAR(50))");
        }

        File outputDir = tempDir.newFolder("identity");
        Configuration config = GspEntityGenerationConfig.create(
            JDBC_URL, JDBC_USER, JDBC_PASSWORD, JDBC_DRIVER,
            SCHEMA, "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", "", false
        );

        try {
            GenerationTool.generate(config);

            File entityDir = new File(outputDir, "jp/co/tis/gsptest/entity");
            String content = Files.readString(new File(entityDir, "IdentityTest.java").toPath());

            assertTrue("IDENTITY戦略", content.contains("GenerationType.IDENTITY"));
            assertFalse("SequenceGeneratorなし", content.contains("@SequenceGenerator"));
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    private int countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }
}
