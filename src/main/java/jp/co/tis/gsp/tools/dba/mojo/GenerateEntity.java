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

package jp.co.tis.gsp.tools.dba.mojo;

import java.io.File;

import jp.co.tis.gsp.tools.dba.entitygen.GspEntityGenerationConfig;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;

/**
 * generate-entity.
 *
 * 指定したスキーマを解析し、Entityクラスを生成する。
 * jOOQ Code Generationを使用してJPA/Doma Entityを生成する。
 *
 * @author kawasima
 */
@Mojo(name = "generate-entity")
public class GenerateEntity extends AbstractDbaMojo {

    /**
     * dicon directory.
     * @deprecated jOOQベースのEntity生成では使用しない。後方互換性のため残置。
     */
    @Deprecated
    @Parameter(defaultValue = "target/classes")
    protected File diconDir;

    /**
     * 無視するテーブル名パターン（正規表現）。
     */
    @Parameter(defaultValue = "(SCHEMA_INFO|.*\\$.*)")
    protected String ignoreTableNamePattern;

    /**
     * エンティティパッケージ名。
     */
    @Parameter(defaultValue = "entity")
    protected String entityPackageName;

    /**
     * gen dialect class name.
     * @deprecated jOOQベースのEntity生成ではDialect自動判定のため不要。指定しても無視される。
     */
    @Deprecated
    @Parameter
    protected String genDialectClassName;

    /**
     * dialect class name.
     * @deprecated jOOQベースのEntity生成ではDialect自動判定のため不要。指定しても無視される。
     */
    @Deprecated
    @Parameter
    protected String dialectClassName;

    /**
     * ルートパッケージ。
     */
    @Parameter(required = true)
    protected String rootPackage;

    /**
     * アクセサ（getter/setter）を生成するか。
     * false（デフォルト）: publicフィールド、true: privateフィールド + getter/setter。
     */
    @Parameter(defaultValue = "false")
    protected Boolean useAccessor;

    /**
     * Entity Javaファイルの出力先ディレクトリ。
     */
    @Parameter(defaultValue = "target/generated-sources/entity/")
    protected File javaFileDestDir;

    /**
     * entity template file.
     * @deprecated jOOQベースのEntity生成ではFreeMarkerテンプレートは使用しない。
     */
    @Deprecated
    @Parameter(defaultValue = "java/gsp_entity.ftl")
    protected String entityTemplate;

    /**
     * template primary directory.
     * @deprecated jOOQベースのEntity生成ではFreeMarkerテンプレートは使用しない。
     */
    @Deprecated
    @Parameter
    protected File templateFilePrimaryDir = null;

    /**
     * {@code @SequenceGenerator}のallocationSize。
     */
    @Parameter(defaultValue = "1")
    protected int allocationSize;

    /**
     * エンティティ種別。"jpa"（デフォルト）または"doma"。
     */
    @Parameter(defaultValue = "jpa")
    protected String entityType;

    /**
     * {@code @Version}付与対象カラム名パターン（正規表現）。
     */
    @Parameter
    protected String versionColumnNamePattern;

    /**
     * JSR310（java.time.*）を使用するか。
     * true: DATE→LocalDate, TIMESTAMP→LocalDateTime。
     */
    @Parameter(defaultValue = "false")
    private Boolean useJSR310;

    /**
     * jOOQ Code Generationを使用してEntityクラスを生成する。
     */
    @Override
    protected void executeMojoSpec() throws MojoExecutionException, MojoFailureException {
        // 非推奨パラメータの警告
        warnDeprecatedParameters();

        String jdbcPassword = (adminPassword == null) ? "" : adminPassword;

        try {
            Configuration config = GspEntityGenerationConfig.create(
                url, adminUser, jdbcPassword, driver,
                schema, rootPackage, entityPackageName,
                javaFileDestDir, entityType,
                ignoreTableNamePattern, useJSR310,
                useAccessor, allocationSize, versionColumnNamePattern
            );

            GenerationTool.generate(config);
        } catch (Exception e) {
            throw new MojoExecutionException("Entity generation failed.", e);
        } finally {
            GspEntityGenerationConfig.clearParams();
        }
    }

    /**
     * 非推奨パラメータが指定されている場合に警告ログを出力する。
     */
    private void warnDeprecatedParameters() {
        if (genDialectClassName != null && !genDialectClassName.isEmpty()) {
            getLog().warn("Parameter 'genDialectClassName' is deprecated and will be ignored. "
                    + "jOOQ handles dialect automatically.");
        }
        if (dialectClassName != null && !dialectClassName.isEmpty()) {
            getLog().warn("Parameter 'dialectClassName' is deprecated and will be ignored. "
                    + "jOOQ handles dialect automatically.");
        }
        if (entityTemplate != null && !"java/gsp_entity.ftl".equals(entityTemplate)) {
            getLog().warn("Parameter 'entityTemplate' is deprecated. "
                    + "jOOQ-based entity generation does not use FreeMarker templates.");
        }
        if (templateFilePrimaryDir != null) {
            getLog().warn("Parameter 'templateFilePrimaryDir' is deprecated. "
                    + "jOOQ-based entity generation does not use FreeMarker templates.");
        }
    }
}
