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

import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Strategy;
import org.jooq.meta.jaxb.Target;

import java.io.File;

/**
 * MojoパラメータからjOOQ Code Generation設定を構築する。
 * S2JDBC-Genのdicon設定ファイル生成を代替する。
 *
 * <p>jOOQはGeneratorをリフレクションで生成するため、
 * Mojoパラメータ（useAccessor, allocationSize, versionColumnNamePattern）は
 * ThreadLocal経由で{@link GspJpaEntityGenerator}に受け渡す。</p>
 */
public class GspEntityGenerationConfig {

    /**
     * Entity生成時のパラメータ。
     * jOOQがリフレクションでGeneratorを生成するため、ThreadLocal経由で受け渡す。
     */
    public static class EntityGenParams {
        private final boolean useAccessor;
        private final int allocationSize;
        private final String versionColumnNamePattern;

        /**
         * @param useAccessor アクセサ（getter/setter）を生成するか
         * @param allocationSize {@code @SequenceGenerator}のallocationSize
         * @param versionColumnNamePattern {@code @Version}付与対象カラム名パターン（正規表現）
         */
        public EntityGenParams(boolean useAccessor, int allocationSize, String versionColumnNamePattern) {
            this.useAccessor = useAccessor;
            this.allocationSize = allocationSize;
            this.versionColumnNamePattern = versionColumnNamePattern;
        }

        /** アクセサ（getter/setter）を生成するか */
        public boolean isUseAccessor() { return useAccessor; }

        /** {@code @SequenceGenerator}のallocationSize */
        public int getAllocationSize() { return allocationSize; }

        /** {@code @Version}アノテーション付与対象のカラム名パターン（正規表現） */
        public String getVersionColumnNamePattern() { return versionColumnNamePattern; }
    }

    /** デフォルトパラメータ（useAccessor=false, allocationSize=1, versionColumnNamePattern=null） */
    private static final EntityGenParams DEFAULT_PARAMS = new EntityGenParams(false, 1, null);

    /** Generator向けパラメータ受け渡し用ThreadLocal */
    private static final ThreadLocal<EntityGenParams> CURRENT_PARAMS = new ThreadLocal<>();

    /**
     * 現在のEntity生成パラメータを取得する。
     * 設定されていない場合はデフォルト値を返す。
     *
     * @return Entity生成パラメータ
     */
    public static EntityGenParams getParams() {
        EntityGenParams params = CURRENT_PARAMS.get();
        return params != null ? params : DEFAULT_PARAMS;
    }

    /**
     * Entity生成パラメータとVIEW解析結果をクリアする。
     * {@code GenerationTool.generate()}完了後に呼び出すこと。
     */
    public static void clearParams() {
        CURRENT_PARAMS.remove();
        GspViewSupport.clear();
        GspRelationSupport.clear();
    }

    private GspEntityGenerationConfig() {
    }

    /**
     * jOOQ Code Generation用の設定オブジェクトを構築する（フルパラメータ版）。
     *
     * <p>Mojoパラメータを全て受け取り、Generator向けのパラメータは
     * ThreadLocalに設定して{@link GspJpaEntityGenerator}から参照可能にする。</p>
     *
     * @param jdbcUrl JDBC URL
     * @param jdbcUser JDBCユーザ
     * @param jdbcPassword JDBCパスワード
     * @param jdbcDriver JDBCドライバクラス名
     * @param schemaName スキーマ名
     * @param rootPackage ルートパッケージ
     * @param entityPackageName エンティティパッケージ名
     * @param javaFileDestDir 出力先ディレクトリ
     * @param entityType エンティティ種別（"jpa" or "doma"）
     * @param ignoreTableNamePattern 無視するテーブル名パターン
     * @param useJSR310 JSR310を使用するか
     * @param useAccessor アクセサ（getter/setter）を生成するか
     * @param allocationSize {@code @SequenceGenerator}のallocationSize
     * @param versionColumnNamePattern {@code @Version}付与対象カラム名パターン
     * @return jOOQ Configuration
     */
    public static Configuration create(
            String jdbcUrl, String jdbcUser, String jdbcPassword, String jdbcDriver,
            String schemaName, String rootPackage, String entityPackageName,
            File javaFileDestDir, String entityType,
            String ignoreTableNamePattern, boolean useJSR310,
            boolean useAccessor, int allocationSize, String versionColumnNamePattern) {

        // Generator向けパラメータをThreadLocalに設定
        CURRENT_PARAMS.set(new EntityGenParams(useAccessor, allocationSize, versionColumnNamePattern));

        // VIEW解析（PKの推定）
        GspViewSupport.analyzeViews(jdbcUrl, jdbcUser, jdbcPassword, schemaName);

        // FK/UniqueConstraint解析（JDBC DatabaseMetaData直接アクセス）
        GspRelationSupport.analyzeRelations(jdbcUrl, jdbcUser, jdbcPassword, schemaName);

        return buildConfiguration(jdbcUrl, jdbcUser, jdbcPassword, jdbcDriver,
                schemaName, rootPackage, entityPackageName,
                javaFileDestDir, entityType, ignoreTableNamePattern, useJSR310);
    }

    /**
     * jOOQ Code Generation用の設定オブジェクトを構築する（後方互換版）。
     *
     * <p>useAccessor=false, allocationSize=1, versionColumnNamePattern=nullで
     * フルパラメータ版を呼び出す。</p>
     *
     * @param jdbcUrl JDBC URL
     * @param jdbcUser JDBCユーザ
     * @param jdbcPassword JDBCパスワード
     * @param jdbcDriver JDBCドライバクラス名
     * @param schemaName スキーマ名
     * @param rootPackage ルートパッケージ
     * @param entityPackageName エンティティパッケージ名
     * @param javaFileDestDir 出力先ディレクトリ
     * @param entityType エンティティ種別（"jpa" or "doma"）
     * @param ignoreTableNamePattern 無視するテーブル名パターン
     * @param useJSR310 JSR310を使用するか
     * @return jOOQ Configuration
     */
    public static Configuration create(
            String jdbcUrl, String jdbcUser, String jdbcPassword, String jdbcDriver,
            String schemaName, String rootPackage, String entityPackageName,
            File javaFileDestDir, String entityType,
            String ignoreTableNamePattern, boolean useJSR310) {

        return create(jdbcUrl, jdbcUser, jdbcPassword, jdbcDriver,
                schemaName, rootPackage, entityPackageName,
                javaFileDestDir, entityType, ignoreTableNamePattern, useJSR310,
                false, 1, null);
    }

    /**
     * jOOQ Configurationオブジェクトを構築する（内部メソッド）。
     */
    private static Configuration buildConfiguration(
            String jdbcUrl, String jdbcUser, String jdbcPassword, String jdbcDriver,
            String schemaName, String rootPackage, String entityPackageName,
            File javaFileDestDir, String entityType,
            String ignoreTableNamePattern, boolean useJSR310) {

        String generatorClassName;
        if ("doma".equalsIgnoreCase(entityType)) {
            generatorClassName = GspDomaEntityGenerator.class.getName();
        } else {
            generatorClassName = GspJpaEntityGenerator.class.getName();
        }

        String targetPackage = rootPackage;
        if (entityPackageName != null && !entityPackageName.isEmpty()) {
            targetPackage = rootPackage + "." + entityPackageName;
        }

        // ignoreTableNamePatternをjOOQのexcludes形式に変換
        String excludes = convertIgnorePattern(ignoreTableNamePattern);

        return new Configuration()
            .withJdbc(new Jdbc()
                .withUrl(jdbcUrl)
                .withUser(jdbcUser)
                .withPassword(jdbcPassword)
                .withDriver(jdbcDriver))
            .withGenerator(new Generator()
                .withName(generatorClassName)
                .withStrategy(new Strategy()
                    .withName(GspGeneratorStrategy.class.getName()))
                .withDatabase(new Database()
                    .withName("org.jooq.meta.jdbc.JDBCDatabase")
                    .withInputSchema(schemaName)
                    .withExcludes(excludes)
                    .withIncludeRoutines(false)
                    .withForcedTypes(GspColumnTypeMapper.createForcedTypes(useJSR310)))
                .withGenerate(new Generate()
                    .withPojos(true)
                    .withTables(false)
                    .withDaos(false)
                    .withRecords(false)
                    .withSequences(false)
                    .withKeys(false)
                    .withIndexes(false)
                    .withFluentSetters(false))
                .withTarget(new Target()
                    .withPackageName(targetPackage)
                    .withDirectory(javaFileDestDir.getAbsolutePath())));
    }

    /**
     * gspのignoreTableNamePattern（正規表現）をjOOQのexcludes形式に変換する。
     * gspのデフォルト: "(SCHEMA_INFO|.*\\$.*)"
     * jOOQのexcludes: パイプ区切りの正規表現
     */
    static String convertIgnorePattern(String ignorePattern) {
        if (ignorePattern == null || ignorePattern.isEmpty()) {
            return "";
        }
        // 先頭・末尾の括弧を除去
        String pattern = ignorePattern;
        if (pattern.startsWith("(") && pattern.endsWith(")")) {
            pattern = pattern.substring(1, pattern.length() - 1);
        }
        return pattern;
    }
}
