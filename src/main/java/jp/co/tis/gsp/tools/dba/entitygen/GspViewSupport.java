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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VIEW対応のサポートクラス。
 *
 * <p>S2JDBC-Genの{@code DbTableMetaReaderWithView}と{@code ViewAnalyzer}の
 * VIEW判定・PK推定ロジックを、jOOQベースのEntity生成向けに移植したもの。</p>
 *
 * <p>VIEWの基底テーブルからPK情報を推定し、Entity生成時に{@code @Id}を付与する。
 * 複雑なVIEW（JOIN、GROUP BY、サブクエリ等）のPKは推定しない。</p>
 *
 * <p>解析結果はThreadLocalに格納し、{@link GspJpaEntityGenerator}から参照する。</p>
 */
public class GspViewSupport {

    /** VIEW名（大文字） → 推定PKカラム名リスト */
    private static final ThreadLocal<Map<String, List<String>>> VIEW_PRIMARY_KEYS = new ThreadLocal<>();

    /** VIEWとして認識されたテーブル名のセット（大文字） */
    private static final ThreadLocal<Set<String>> VIEW_NAMES = new ThreadLocal<>();

    /** FROM句からテーブル名を抽出する正規表現 */
    private static final Pattern FROM_PATTERN = Pattern.compile(
        "\\bFROM\\s+\"?([\\w.]+)\"?\\.?\"?([\\w]+)?\"?",
        Pattern.CASE_INSENSITIVE
    );

    /** JOIN検出用パターン（JOINが含まれるVIEWは複雑とみなす） */
    private static final Pattern JOIN_PATTERN = Pattern.compile(
        "\\bJOIN\\b", Pattern.CASE_INSENSITIVE
    );

    /** GROUP BY検出用パターン */
    private static final Pattern GROUP_BY_PATTERN = Pattern.compile(
        "\\bGROUP\\s+BY\\b", Pattern.CASE_INSENSITIVE
    );

    /** UNION検出用パターン */
    private static final Pattern UNION_PATTERN = Pattern.compile(
        "\\bUNION\\b", Pattern.CASE_INSENSITIVE
    );

    private GspViewSupport() {
    }

    /**
     * VIEWを解析してPK情報を推定する。
     * {@code GenerationTool.generate()}呼び出し前に実行すること。
     *
     * @param jdbcUrl JDBC URL
     * @param user JDBCユーザ
     * @param password JDBCパスワード
     * @param schemaName スキーマ名
     */
    public static void analyzeViews(String jdbcUrl, String user, String password, String schemaName) {
        Map<String, List<String>> viewPKs = new HashMap<>();
        Set<String> viewNames = new HashSet<>();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            DatabaseMetaData metaData = conn.getMetaData();

            // VIEWを列挙
            try (ResultSet rs = metaData.getTables(null, schemaName, null, new String[]{"VIEW"})) {
                while (rs.next()) {
                    String viewName = rs.getString("TABLE_NAME");
                    viewNames.add(viewName.toUpperCase());
                }
            }

            // 各VIEWの定義を取得してPKを推定
            for (String viewName : viewNames) {
                String viewDef = getViewDefinition(conn, schemaName, viewName);
                if (viewDef == null) continue;

                String baseTable = extractBaseTable(viewDef);
                if (baseTable == null) continue;

                // VIEWのカラム名一覧を取得
                Set<String> viewColumns = getColumnNames(metaData, schemaName, viewName);

                // ベーステーブルのPKを取得
                List<String> basePKs = getPrimaryKeys(metaData, schemaName, baseTable);
                if (basePKs.isEmpty()) continue;

                // VIEWにPKカラムが全て含まれるか確認
                List<String> inferredPKs = new ArrayList<>();
                boolean allPKsPresent = true;
                for (String pk : basePKs) {
                    if (viewColumns.contains(pk.toUpperCase())) {
                        inferredPKs.add(pk);
                    } else {
                        allPKsPresent = false;
                        break;
                    }
                }

                if (allPKsPresent && !inferredPKs.isEmpty()) {
                    viewPKs.put(viewName.toUpperCase(), inferredPKs);
                }
            }
        } catch (SQLException e) {
            // VIEW解析に失敗してもEntity生成は続行する（PKなしのEntityが生成される）
            System.err.println("[WARN] VIEW analysis failed: " + e.getMessage());
        }

        VIEW_PRIMARY_KEYS.set(viewPKs);
        VIEW_NAMES.set(viewNames);
    }

    /**
     * 指定されたテーブル名がVIEWであるかを判定する。
     *
     * @param tableName テーブル名
     * @return VIEWの場合true
     */
    public static boolean isView(String tableName) {
        Set<String> names = VIEW_NAMES.get();
        return names != null && names.contains(tableName.toUpperCase());
    }

    /**
     * VIEWの推定PKカラム名リストを返す。
     *
     * @param viewName VIEW名
     * @return 推定PKカラム名リスト（推定できない場合は空リスト）
     */
    public static List<String> getInferredPrimaryKeys(String viewName) {
        Map<String, List<String>> map = VIEW_PRIMARY_KEYS.get();
        if (map == null) return Collections.emptyList();
        return map.getOrDefault(viewName.toUpperCase(), Collections.emptyList());
    }

    /**
     * ThreadLocalをクリアする。
     * {@code GenerationTool.generate()}完了後に呼び出すこと。
     */
    public static void clear() {
        VIEW_PRIMARY_KEYS.remove();
        VIEW_NAMES.remove();
    }

    /**
     * VIEW定義SQLを取得する（INFORMATION_SCHEMA経由）。
     */
    static String getViewDefinition(Connection conn, String schemaName, String viewName) throws SQLException {
        String sql = "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("VIEW_DEFINITION");
                }
            }
        }
        return null;
    }

    /**
     * VIEW定義SQLから基底テーブル名を抽出する。
     * 複雑なVIEW（JOIN/GROUP BY/UNION/サブクエリ）はnullを返す。
     *
     * @param viewDefinition VIEW定義SQL
     * @return 基底テーブル名（複雑なVIEWの場合null）
     */
    static String extractBaseTable(String viewDefinition) {
        if (viewDefinition == null) return null;

        // 複雑なVIEWを検出
        if (JOIN_PATTERN.matcher(viewDefinition).find()) return null;
        if (GROUP_BY_PATTERN.matcher(viewDefinition).find()) return null;
        if (UNION_PATTERN.matcher(viewDefinition).find()) return null;

        // FROM句からテーブル名を抽出
        Matcher matcher = FROM_PATTERN.matcher(viewDefinition);
        if (matcher.find()) {
            // schema.table or table のパターンに対応
            String part1 = matcher.group(1);
            String part2 = matcher.group(2);
            // schema.table の場合は part2 がテーブル名
            String tableName = (part2 != null && !part2.isEmpty()) ? part2 : part1;
            // ダブルクォートを除去
            return tableName.replaceAll("\"", "").toUpperCase();
        }

        return null;
    }

    /**
     * テーブルのカラム名一覧を取得する。
     */
    private static Set<String> getColumnNames(DatabaseMetaData metaData, String schemaName, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = metaData.getColumns(null, schemaName, tableName, null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toUpperCase());
            }
        }
        return columns;
    }

    /**
     * テーブルのPKカラム名リストを取得する。
     */
    private static List<String> getPrimaryKeys(DatabaseMetaData metaData, String schemaName, String tableName) throws SQLException {
        List<String> pks = new ArrayList<>();
        try (ResultSet rs = metaData.getPrimaryKeys(null, schemaName, tableName)) {
            while (rs.next()) {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }
}
