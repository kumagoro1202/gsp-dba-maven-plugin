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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link GspRelationSupport}のテスト。
 * H2インメモリDBを使用してFK/ユニーク制約の解析を検証する。
 */
public class GspRelationSupportTest {

    private static final String JDBC_URL = "jdbc:h2:mem:relsupportdb;DB_CLOSE_DELAY=-1";
    private static final String JDBC_USER = "sa";
    private static final String JDBC_PASSWORD = "";
    private static final String SCHEMA = "PUBLIC";

    private Connection connection;

    @Before
    public void setUp() throws Exception {
        Class.forName("org.h2.Driver");
        connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
    }

    @After
    public void tearDown() throws Exception {
        GspRelationSupport.clear();
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
    }

    @Test
    public void testSingleColumnFK() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE PARENT (PARENT_ID BIGINT NOT NULL PRIMARY KEY, NAME VARCHAR(50))");
            stmt.execute("CREATE TABLE CHILD (CHILD_ID BIGINT NOT NULL PRIMARY KEY, "
                + "PARENT_ID BIGINT, FOREIGN KEY (PARENT_ID) REFERENCES PARENT(PARENT_ID))");
        }

        GspRelationSupport.analyzeRelations(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);

        // forward FK: CHILD → PARENT
        List<GspRelationSupport.ForeignKeyDef> childFKs = GspRelationSupport.getForeignKeys("CHILD");
        assertEquals("CHILDのFK数", 1, childFKs.size());

        GspRelationSupport.ForeignKeyDef fk = childFKs.get(0);
        assertEquals("FK先テーブル", "PARENT", fk.getPkTableName());
        assertEquals("FKカラム数", 1, fk.getColumns().size());
        assertEquals("FKカラム名", "PARENT_ID", fk.getColumns().get(0).getFkColumnName());
        assertEquals("PKカラム名", "PARENT_ID", fk.getColumns().get(0).getPkColumnName());

        // inverse FK: PARENT ← CHILD
        List<GspRelationSupport.ForeignKeyDef> parentInvFKs = GspRelationSupport.getInverseForeignKeys("PARENT");
        assertEquals("PARENTのinverse FK数", 1, parentInvFKs.size());
        assertEquals("inverse FK元テーブル", "CHILD", parentInvFKs.get(0).getFkTableName());
    }

    @Test
    public void testCompositeFK() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE MASTER ("
                + "  KEY1 BIGINT NOT NULL, KEY2 BIGINT NOT NULL,"
                + "  VAL VARCHAR(50),"
                + "  PRIMARY KEY (KEY1, KEY2))");
            stmt.execute("CREATE TABLE DETAIL ("
                + "  DETAIL_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  MASTER_KEY1 BIGINT, MASTER_KEY2 BIGINT,"
                + "  FOREIGN KEY (MASTER_KEY1, MASTER_KEY2) REFERENCES MASTER(KEY1, KEY2))");
        }

        GspRelationSupport.analyzeRelations(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);

        List<GspRelationSupport.ForeignKeyDef> detailFKs = GspRelationSupport.getForeignKeys("DETAIL");
        assertEquals("DETAILのFK数", 1, detailFKs.size());

        GspRelationSupport.ForeignKeyDef fk = detailFKs.get(0);
        assertEquals("複合FKカラム数", 2, fk.getColumns().size());
        assertEquals("FKカラム1", "MASTER_KEY1", fk.getColumns().get(0).getFkColumnName());
        assertEquals("PKカラム1", "KEY1", fk.getColumns().get(0).getPkColumnName());
        assertEquals("FKカラム2", "MASTER_KEY2", fk.getColumns().get(1).getFkColumnName());
        assertEquals("PKカラム2", "KEY2", fk.getColumns().get(1).getPkColumnName());
    }

    @Test
    public void testMultipleFKsFromSameTable() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE USERS (USER_ID BIGINT NOT NULL PRIMARY KEY)");
            stmt.execute("CREATE TABLE MESSAGES ("
                + "  MSG_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  SENDER_ID BIGINT,"
                + "  RECEIVER_ID BIGINT,"
                + "  FOREIGN KEY (SENDER_ID) REFERENCES USERS(USER_ID),"
                + "  FOREIGN KEY (RECEIVER_ID) REFERENCES USERS(USER_ID))");
        }

        GspRelationSupport.analyzeRelations(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);

        List<GspRelationSupport.ForeignKeyDef> msgFKs = GspRelationSupport.getForeignKeys("MESSAGES");
        assertEquals("MESSAGESのFK数", 2, msgFKs.size());

        // inverse: USERS ← MESSAGES (2つ)
        List<GspRelationSupport.ForeignKeyDef> userInvFKs = GspRelationSupport.getInverseForeignKeys("USERS");
        assertEquals("USERSのinverse FK数", 2, userInvFKs.size());
    }

    @Test
    public void testUniqueConstraint() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE PRODUCTS ("
                + "  PRODUCT_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  CODE VARCHAR(50) NOT NULL,"
                + "  CATEGORY VARCHAR(50) NOT NULL,"
                + "  CONSTRAINT UK_CODE_CAT UNIQUE (CODE, CATEGORY))");
        }

        GspRelationSupport.analyzeRelations(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);

        Map<String, List<String>> uks = GspRelationSupport.getUniqueConstraints("PRODUCTS");
        assertFalse("ユニーク制約あり", uks.isEmpty());

        // UK_CODE_CATを探す
        boolean found = false;
        for (Map.Entry<String, List<String>> entry : uks.entrySet()) {
            List<String> cols = entry.getValue();
            if (cols.contains("CODE") && cols.contains("CATEGORY")) {
                found = true;
                assertEquals("ユニーク制約カラム数", 2, cols.size());
            }
        }
        assertTrue("CODE+CATEGORYのユニーク制約が見つかる", found);
    }

    @Test
    public void testNoFK() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE STANDALONE (ID BIGINT NOT NULL PRIMARY KEY, NAME VARCHAR(50))");
        }

        GspRelationSupport.analyzeRelations(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);

        List<GspRelationSupport.ForeignKeyDef> fks = GspRelationSupport.getForeignKeys("STANDALONE");
        assertTrue("FK なし", fks.isEmpty());

        List<GspRelationSupport.ForeignKeyDef> invFks = GspRelationSupport.getInverseForeignKeys("STANDALONE");
        assertTrue("inverse FK なし", invFks.isEmpty());
    }

    @Test
    public void testCaseInsensitiveTableName() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE MyTable (ID BIGINT NOT NULL PRIMARY KEY)");
            stmt.execute("CREATE TABLE RefTable (ID BIGINT NOT NULL PRIMARY KEY, "
                + "MY_ID BIGINT, FOREIGN KEY (MY_ID) REFERENCES MyTable(ID))");
        }

        GspRelationSupport.analyzeRelations(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);

        // H2はテーブル名を大文字化するため、大文字で検索
        List<GspRelationSupport.ForeignKeyDef> fks = GspRelationSupport.getForeignKeys("REFTABLE");
        assertEquals("FK数", 1, fks.size());
    }

    @Test
    public void testClearRemovesData() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE T1 (ID BIGINT NOT NULL PRIMARY KEY)");
            stmt.execute("CREATE TABLE T2 (ID BIGINT NOT NULL PRIMARY KEY, "
                + "T1_ID BIGINT, FOREIGN KEY (T1_ID) REFERENCES T1(ID))");
        }

        GspRelationSupport.analyzeRelations(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);
        assertFalse("clear前: FKあり", GspRelationSupport.getForeignKeys("T2").isEmpty());

        GspRelationSupport.clear();
        assertTrue("clear後: FK空", GspRelationSupport.getForeignKeys("T2").isEmpty());
    }

    @Test
    public void testPKConstraintExcludedFromUniqueConstraints() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE PK_ONLY ("
                + "  ID BIGINT NOT NULL PRIMARY KEY,"
                + "  NAME VARCHAR(50))");
        }

        GspRelationSupport.analyzeRelations(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);

        Map<String, List<String>> uks = GspRelationSupport.getUniqueConstraints("PK_ONLY");
        assertTrue("PK制約はユニーク制約に含まれない", uks.isEmpty());
    }

    @Test
    public void testSelfReferentialFK() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE TREE_NODE ("
                + "  NODE_ID BIGINT NOT NULL PRIMARY KEY,"
                + "  PARENT_NODE_ID BIGINT,"
                + "  FOREIGN KEY (PARENT_NODE_ID) REFERENCES TREE_NODE(NODE_ID))");
        }

        GspRelationSupport.analyzeRelations(JDBC_URL, JDBC_USER, JDBC_PASSWORD, SCHEMA);

        List<GspRelationSupport.ForeignKeyDef> fks = GspRelationSupport.getForeignKeys("TREE_NODE");
        assertEquals("自己参照FK数", 1, fks.size());
        assertEquals("自己参照FK先", "TREE_NODE", fks.get(0).getPkTableName());

        List<GspRelationSupport.ForeignKeyDef> invFks = GspRelationSupport.getInverseForeignKeys("TREE_NODE");
        assertEquals("自己参照inverse FK数", 1, invFks.size());
    }
}
