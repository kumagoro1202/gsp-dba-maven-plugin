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
 */
public class GspEntityGenerationConfig {

    private GspEntityGenerationConfig() {
    }

    /**
     * jOOQ Code Generation用の設定オブジェクトを構築する。
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

        String generatorClassName = GspJpaEntityGenerator.class.getName();

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
