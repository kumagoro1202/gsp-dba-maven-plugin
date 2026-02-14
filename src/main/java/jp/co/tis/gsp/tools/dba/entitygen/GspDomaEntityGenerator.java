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

import org.jooq.codegen.GeneratorStrategy.Mode;
import org.jooq.codegen.JavaGenerator;
import org.jooq.codegen.JavaWriter;
import org.jooq.meta.ColumnDefinition;
import org.jooq.meta.DataTypeDefinition;
import org.jooq.meta.SchemaDefinition;
import org.jooq.meta.TableDefinition;
import org.jooq.meta.UniqueKeyDefinition;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * jOOQ JavaGeneratorを拡張し、Domaアノテーション付きEntityを生成する。
 *
 * <p>生成されるEntityの特徴:</p>
 * <ul>
 *   <li>Domaアノテーション（{@code org.seasar.doma.*}）使用</li>
 *   <li>{@code @Generated("GSP")} アノテーション付与</li>
 *   <li>{@code @Entity}, {@code @Table}, {@code @Column}, {@code @Id} 等</li>
 *   <li>{@link Serializable}実装</li>
 *   <li>Doma規約に従い、常にgetter/setterを生成</li>
 *   <li>useAccessor=false時: publicフィールド + getter/setter</li>
 *   <li>useAccessor=true時: privateフィールド + getter/setter</li>
 * </ul>
 *
 * <p>{@code @Column}はname属性のみ（JPA版と異なりlength/precision/nullable等は含まない）。</p>
 */
public class GspDomaEntityGenerator extends JavaGenerator {

    @Override
    protected void generatePojo(TableDefinition table, JavaWriter out) {
        // パラメータ取得
        GspEntityGenerationConfig.EntityGenParams params = GspEntityGenerationConfig.getParams();
        int allocationSize = params.getAllocationSize();
        boolean useAccessor = params.isUseAccessor();
        String versionPattern = params.getVersionColumnNamePattern();

        String className = getStrategy().getJavaClassName(table, Mode.POJO);
        String packageName = getStrategy().getJavaPackageName(table, Mode.POJO);
        List<ColumnDefinition> columns = table.getColumns();

        // VIEW判定
        boolean isViewTable = GspViewSupport.isView(table.getOutputName());

        // バージョンカラムの事前判定（import生成に必要）
        boolean hasVersionColumn = false;
        if (versionPattern != null && !versionPattern.isEmpty()) {
            hasVersionColumn = columns.stream()
                .anyMatch(c -> c.getOutputName().matches(versionPattern));
        }

        // パッケージ宣言
        if (packageName != null && !packageName.isEmpty()) {
            out.println("package %s;", packageName);
            out.println();
        }

        // import文
        out.println("import java.io.Serializable;");
        out.println("import jakarta.annotation.Generated;");
        out.println("import org.seasar.doma.Column;");
        out.println("import org.seasar.doma.Entity;");
        out.println("import org.seasar.doma.Table;");

        boolean hasId = hasPrimaryKey(table);
        if (hasId) {
            out.println("import org.seasar.doma.Id;");
        }

        boolean hasIdentity = hasIdentityColumn(table);
        if (hasId && !isViewTable) {
            out.println("import org.seasar.doma.GeneratedValue;");
            out.println("import org.seasar.doma.GenerationType;");
            if (!hasIdentity) {
                out.println("import org.seasar.doma.SequenceGenerator;");
            }
        }

        if (hasVersionColumn) {
            out.println("import org.seasar.doma.Version;");
        }

        // 型インポート
        addTypeImports(out, columns);

        out.println();

        // Javadoc
        out.println("/**");
        out.println(" * %sエンティティクラス", className);
        out.println(" */");

        // クラスアノテーション
        out.println("@Generated(\"GSP\")");
        out.println("@Entity");

        // @Table
        SchemaDefinition schema = table.getSchema();
        String schemaName = schema != null ? schema.getOutputName() : null;
        String tableName = table.getOutputName();

        if (schemaName != null && !schemaName.isEmpty() && !"PUBLIC".equals(schemaName)) {
            out.println("@Table(schema = \"%s\", name = \"%s\")", schemaName, tableName);
        } else {
            out.println("@Table(name = \"%s\")", tableName);
        }

        // クラス宣言
        out.println("public class %s implements Serializable {", className);
        out.println();
        out.println("    private static final long serialVersionUID = 1L;");

        // フィールド情報を収集（getter/setter生成用）
        List<String> fieldNames = new ArrayList<>();
        List<String> fieldSimpleTypes = new ArrayList<>();

        // フィールド
        String accessModifier = useAccessor ? "private" : "public";

        for (ColumnDefinition column : columns) {
            out.println();
            String fieldName = getStrategy().getJavaMemberName(column, Mode.POJO);
            String javaType = getJavaType(column);
            boolean isPk = isPrimaryKey(table, column);

            // コメント
            String comment = column.getComment();
            if (comment != null && !comment.isEmpty()) {
                out.println("    /** %s */", comment);
            } else {
                out.println("    /** %sプロパティ */", fieldName);
            }

            // @Id
            if (isPk) {
                out.println("    @Id");

                // @GeneratedValue（VIEWの場合は生成しない）
                if (!isViewTable) {
                    if (hasIdentity) {
                        out.println("    @GeneratedValue(strategy = GenerationType.IDENTITY)");
                    } else {
                        String seqName = column.getOutputName() + "_SEQ";
                        if (schemaName != null && !schemaName.isEmpty() && !"PUBLIC".equals(schemaName)) {
                            seqName = schemaName + "." + seqName;
                        }
                        out.println("    @GeneratedValue(strategy = GenerationType.SEQUENCE)");
                        out.println("    @SequenceGenerator(sequence = \"%s\", initialValue = 1, allocationSize = %d)", seqName, allocationSize);
                    }
                }
            }

            // @Version
            if (versionPattern != null && !versionPattern.isEmpty()
                    && column.getOutputName().matches(versionPattern)) {
                out.println("    @Version");
            }

            // @Column（Domaはname属性のみ）
            out.println("    @Column(name = \"%s\")", column.getOutputName());

            // フィールド宣言
            String simpleType = getSimpleJavaType(javaType);
            out.println("    %s %s %s;", accessModifier, simpleType, fieldName);

            // getter/setter用にフィールド情報を保存
            fieldNames.add(fieldName);
            fieldSimpleTypes.add(simpleType);
        }

        // getter/setter（Doma規約: 常に生成）
        for (int i = 0; i < fieldNames.size(); i++) {
            String fieldName = fieldNames.get(i);
            String simpleType = fieldSimpleTypes.get(i);
            String capitalizedName = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            String getterPrefix = "boolean".equals(simpleType) || "Boolean".equals(simpleType) ? "is" : "get";

            out.println();
            out.println("    public %s %s%s() {", simpleType, getterPrefix, capitalizedName);
            out.println("        return %s;", fieldName);
            out.println("    }");
            out.println();
            out.println("    public void set%s(%s %s) {", capitalizedName, simpleType, fieldName);
            out.println("        this.%s = %s;", fieldName, fieldName);
            out.println("    }");
        }

        // クラスの閉じ
        out.println("}");
    }

    /**
     * テーブルにPKがあるかどうかを判定する。
     * VIEWの場合は{@link GspViewSupport}の推定PKを確認する。
     */
    private boolean hasPrimaryKey(TableDefinition table) {
        UniqueKeyDefinition pk = table.getPrimaryKey();
        if (pk != null && !pk.getKeyColumns().isEmpty()) return true;
        return !getInferredOrActualPKs(table).isEmpty();
    }

    /**
     * カラムがPKに含まれるかを判定する。
     */
    private boolean isPrimaryKey(TableDefinition table, ColumnDefinition column) {
        UniqueKeyDefinition pk = table.getPrimaryKey();
        if (pk != null) {
            return pk.getKeyColumns().stream()
                .anyMatch(c -> c.getName().equals(column.getName()));
        }
        List<String> inferredPKs = getInferredOrActualPKs(table);
        return inferredPKs.stream()
            .anyMatch(pkName -> pkName.equalsIgnoreCase(column.getName()));
    }

    /**
     * IDENTITY（AUTO_INCREMENT）カラムが存在するかを判定する。
     */
    private boolean hasIdentityColumn(TableDefinition table) {
        return table.getColumns().stream().anyMatch(ColumnDefinition::isIdentity);
    }

    /**
     * VIEWの推定PKカラム名リストを返す。VIEWでない場合は空リスト。
     */
    private List<String> getInferredOrActualPKs(TableDefinition table) {
        if (GspViewSupport.isView(table.getOutputName())) {
            return GspViewSupport.getInferredPrimaryKeys(table.getOutputName());
        }
        return Collections.emptyList();
    }

    /**
     * カラムのJava型名を取得する。
     */
    private String getJavaType(ColumnDefinition column) {
        DataTypeDefinition type = column.getType();
        String userType = type.getUserType();
        if (userType != null && !userType.isEmpty()) {
            return userType;
        }
        return GspColumnTypeMapper.getJavaType(type);
    }

    /**
     * import文に必要な型を追加する。
     */
    private void addTypeImports(JavaWriter out, List<ColumnDefinition> columns) {
        boolean needsSqlDate = false;
        boolean needsSqlTime = false;
        boolean needsSqlTimestamp = false;
        boolean needsBigDecimal = false;
        boolean needsLocalDate = false;
        boolean needsLocalTime = false;
        boolean needsLocalDateTime = false;

        for (ColumnDefinition column : columns) {
            String javaType = getJavaType(column);
            if ("java.sql.Date".equals(javaType)) needsSqlDate = true;
            if ("java.sql.Time".equals(javaType)) needsSqlTime = true;
            if ("java.sql.Timestamp".equals(javaType)) needsSqlTimestamp = true;
            if ("java.math.BigDecimal".equals(javaType)) needsBigDecimal = true;
            if ("java.time.LocalDate".equals(javaType)) needsLocalDate = true;
            if ("java.time.LocalTime".equals(javaType)) needsLocalTime = true;
            if ("java.time.LocalDateTime".equals(javaType)) needsLocalDateTime = true;
        }

        if (needsBigDecimal) out.println("import java.math.BigDecimal;");
        if (needsSqlDate) out.println("import java.sql.Date;");
        if (needsSqlTime) out.println("import java.sql.Time;");
        if (needsSqlTimestamp) out.println("import java.sql.Timestamp;");
        if (needsLocalDate) out.println("import java.time.LocalDate;");
        if (needsLocalDateTime) out.println("import java.time.LocalDateTime;");
        if (needsLocalTime) out.println("import java.time.LocalTime;");
    }

    /**
     * FQCN→単純クラス名を返す。
     */
    private String getSimpleJavaType(String fqcn) {
        if (fqcn.startsWith("java.lang.")) return fqcn.substring("java.lang.".length());
        if (fqcn.startsWith("java.sql.")) return fqcn.substring("java.sql.".length());
        if (fqcn.startsWith("java.math.")) return fqcn.substring("java.math.".length());
        if (fqcn.startsWith("java.time.")) return fqcn.substring("java.time.".length());
        return fqcn;
    }
}
