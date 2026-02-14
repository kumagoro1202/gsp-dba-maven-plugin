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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC DatabaseMetaDataからFK（外部キー）情報を収集し、ThreadLocal経由でGeneratorに受け渡す。
 *
 * <p>jOOQの{@code TableDefinition.getForeignKeys()}がカスタムGenerator内で
 * 空リストを返す問題を回避するため、JDBC APIで直接FK情報を取得する。</p>
 *
 * <p>ライフサイクル:</p>
 * <ol>
 *   <li>{@link #analyzeRelations}で解析開始（GenerationTool.generate前に呼ぶ）</li>
 *   <li>Generator内で{@link #getForeignKeys}等を呼ぶ</li>
 *   <li>{@link #clear}でThreadLocalを解放</li>
 * </ol>
 */
public class GspRelationSupport {

    /**
     * 単一のFK参照情報（FKカラム→PKテーブル.PKカラム）。
     */
    public static class ForeignKeyColumn {
        private final String fkColumnName;
        private final String pkTableName;
        private final String pkColumnName;
        private final String fkName;
        private final int keySeq;

        public ForeignKeyColumn(String fkColumnName, String pkTableName, String pkColumnName,
                                 String fkName, int keySeq) {
            this.fkColumnName = fkColumnName;
            this.pkTableName = pkTableName;
            this.pkColumnName = pkColumnName;
            this.fkName = fkName;
            this.keySeq = keySeq;
        }

        public String getFkColumnName() { return fkColumnName; }
        public String getPkTableName() { return pkTableName; }
        public String getPkColumnName() { return pkColumnName; }
        public String getFkName() { return fkName; }
        public int getKeySeq() { return keySeq; }
    }

    /**
     * 名前付きFK制約（1つのFK制約は1つ以上のカラムで構成される）。
     */
    public static class ForeignKeyDef {
        private final String fkName;
        private final String fkTableName;
        private final String pkTableName;
        private final List<ForeignKeyColumn> columns;

        public ForeignKeyDef(String fkName, String fkTableName, String pkTableName,
                              List<ForeignKeyColumn> columns) {
            this.fkName = fkName;
            this.fkTableName = fkTableName;
            this.pkTableName = pkTableName;
            this.columns = Collections.unmodifiableList(columns);
        }

        public String getFkName() { return fkName; }
        public String getFkTableName() { return fkTableName; }
        public String getPkTableName() { return pkTableName; }
        public List<ForeignKeyColumn> getColumns() { return columns; }
    }

    /**
     * テーブル名→そのテーブルが持つFK制約リスト（forward FK: このテーブルが参照する側）。
     */
    private static final ThreadLocal<Map<String, List<ForeignKeyDef>>> FORWARD_FKS = new ThreadLocal<>();

    /**
     * テーブル名→そのテーブルを参照しているFK制約リスト（inverse FK: このテーブルが参照される側）。
     */
    private static final ThreadLocal<Map<String, List<ForeignKeyDef>>> INVERSE_FKS = new ThreadLocal<>();

    /**
     * テーブル名→非PKユニーク制約のカラムリスト。
     * 各エントリは制約名→カラム名リストのマップ。
     */
    private static final ThreadLocal<Map<String, Map<String, List<String>>>> UNIQUE_CONSTRAINTS = new ThreadLocal<>();

    private GspRelationSupport() {
    }

    /**
     * JDBC DatabaseMetaDataからFK情報・ユニーク制約情報を収集する。
     *
     * @param jdbcUrl JDBC URL
     * @param jdbcUser JDBCユーザ
     * @param jdbcPassword JDBCパスワード
     * @param schemaName スキーマ名
     */
    public static void analyzeRelations(String jdbcUrl, String jdbcUser, String jdbcPassword,
                                         String schemaName) {
        Map<String, List<ForeignKeyDef>> forwardMap = new LinkedHashMap<>();
        Map<String, List<ForeignKeyDef>> inverseMap = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> uniqueMap = new LinkedHashMap<>();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            DatabaseMetaData meta = conn.getMetaData();

            // テーブル一覧取得
            List<String> tableNames = new ArrayList<>();
            try (ResultSet rs = meta.getTables(null, schemaName, null, new String[]{"TABLE"})) {
                while (rs.next()) {
                    tableNames.add(rs.getString("TABLE_NAME"));
                }
            }

            // 各テーブルのFK情報を収集
            for (String tableName : tableNames) {
                collectForeignKeys(meta, schemaName, tableName, forwardMap, inverseMap);
                collectUniqueConstraints(meta, schemaName, tableName, uniqueMap);
            }
        } catch (SQLException e) {
            // FK解析失敗時はEntityにFK情報なしで生成を続行
            System.err.println("[GspRelationSupport] FK analysis failed: " + e.getMessage());
        }

        FORWARD_FKS.set(forwardMap);
        INVERSE_FKS.set(inverseMap);
        UNIQUE_CONSTRAINTS.set(uniqueMap);
    }

    /**
     * 指定テーブルのFK制約リスト（このテーブルが参照する側）を返す。
     */
    public static List<ForeignKeyDef> getForeignKeys(String tableName) {
        Map<String, List<ForeignKeyDef>> map = FORWARD_FKS.get();
        if (map == null) return Collections.emptyList();
        return map.getOrDefault(tableName.toUpperCase(), Collections.emptyList());
    }

    /**
     * 指定テーブルを参照しているFK制約リスト（逆方向FK）を返す。
     */
    public static List<ForeignKeyDef> getInverseForeignKeys(String tableName) {
        Map<String, List<ForeignKeyDef>> map = INVERSE_FKS.get();
        if (map == null) return Collections.emptyList();
        return map.getOrDefault(tableName.toUpperCase(), Collections.emptyList());
    }

    /**
     * 指定テーブルの非PKユニーク制約を返す。
     * キー=制約名、値=カラム名リスト。
     */
    public static Map<String, List<String>> getUniqueConstraints(String tableName) {
        Map<String, Map<String, List<String>>> map = UNIQUE_CONSTRAINTS.get();
        if (map == null) return Collections.emptyMap();
        return map.getOrDefault(tableName.toUpperCase(), Collections.emptyMap());
    }

    /**
     * ThreadLocalをクリアする。
     */
    public static void clear() {
        FORWARD_FKS.remove();
        INVERSE_FKS.remove();
        UNIQUE_CONSTRAINTS.remove();
    }

    /**
     * 指定テーブルのFK情報をJDBC DatabaseMetaDataから収集し、forwardMap/inverseMapに追加する。
     */
    private static void collectForeignKeys(DatabaseMetaData meta, String schemaName,
                                            String tableName,
                                            Map<String, List<ForeignKeyDef>> forwardMap,
                                            Map<String, List<ForeignKeyDef>> inverseMap)
            throws SQLException {

        // テーブルごとのFK情報をFK名でグルーピング
        Map<String, List<ForeignKeyColumn>> fkGroups = new LinkedHashMap<>();
        Map<String, String> fkToPkTable = new LinkedHashMap<>();

        try (ResultSet rs = meta.getImportedKeys(null, schemaName, tableName)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                String fkColumnName = rs.getString("FKCOLUMN_NAME");
                String pkTableName = rs.getString("PKTABLE_NAME");
                String pkColumnName = rs.getString("PKCOLUMN_NAME");
                int keySeq = rs.getInt("KEY_SEQ");

                fkGroups.computeIfAbsent(fkName, k -> new ArrayList<>())
                    .add(new ForeignKeyColumn(fkColumnName, pkTableName, pkColumnName, fkName, keySeq));
                fkToPkTable.put(fkName, pkTableName);
            }
        }

        // グループ化されたFK情報をForeignKeyDefに変換
        for (Map.Entry<String, List<ForeignKeyColumn>> entry : fkGroups.entrySet()) {
            String fkName = entry.getKey();
            List<ForeignKeyColumn> cols = entry.getValue();
            String pkTableName = fkToPkTable.get(fkName);

            // KEY_SEQでソート（複合FK順序保証）
            cols.sort((a, b) -> Integer.compare(a.getKeySeq(), b.getKeySeq()));

            ForeignKeyDef fkDef = new ForeignKeyDef(fkName, tableName, pkTableName, cols);

            // forward FK: このテーブルが参照する
            forwardMap.computeIfAbsent(tableName.toUpperCase(), k -> new ArrayList<>()).add(fkDef);

            // inverse FK: 参照先テーブルから見た逆方向
            inverseMap.computeIfAbsent(pkTableName.toUpperCase(), k -> new ArrayList<>()).add(fkDef);
        }
    }

    /**
     * 指定テーブルの非PKユニーク制約をJDBC DatabaseMetaDataから収集する。
     */
    private static void collectUniqueConstraints(DatabaseMetaData meta, String schemaName,
                                                   String tableName,
                                                   Map<String, Map<String, List<String>>> uniqueMap)
            throws SQLException {

        // PK制約名を取得（除外用）
        String pkConstraintName = null;
        try (ResultSet rs = meta.getPrimaryKeys(null, schemaName, tableName)) {
            if (rs.next()) {
                pkConstraintName = rs.getString("PK_NAME");
            }
        }

        // インデックス情報からユニーク制約を収集
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(null, schemaName, tableName, true, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                boolean nonUnique = rs.getBoolean("NON_UNIQUE");

                if (indexName == null || columnName == null || nonUnique) continue;
                // PK制約は除外
                if (indexName.equals(pkConstraintName)) continue;
                // PRIMARY_KEY系の名前パターンも除外
                if (indexName.startsWith("PRIMARY_KEY") || indexName.startsWith("PK_")) continue;

                constraints.computeIfAbsent(indexName, k -> new ArrayList<>()).add(columnName);
            }
        }

        if (!constraints.isEmpty()) {
            uniqueMap.put(tableName.toUpperCase(), constraints);
        }
    }
}
