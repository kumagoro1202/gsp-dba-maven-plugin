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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jOOQ JavaGeneratorを拡張し、gsp-dba-maven-plugin互換のJPA Entityを生成する。
 *
 * <p>生成されるEntityの特徴:</p>
 * <ul>
 *   <li>Jakarta Persistence API（jakarta.persistence.*）使用</li>
 *   <li>{@code @Generated("GSP")} アノテーション付与</li>
 *   <li>{@code @Entity}, {@code @Table}, {@code @Column}, {@code @Id} 等</li>
 *   <li>{@link Serializable}実装</li>
 *   <li>useAccessor=false時: publicフィールド（gspデフォルト）</li>
 *   <li>useAccessor=true時: privateフィールド + getter/setter</li>
 *   <li>versionColumnNamePattern指定時: 一致カラムに{@code @Version}付与</li>
 *   <li>FK関連: {@code @ManyToOne}, {@code @OneToMany}, {@code @JoinColumn}</li>
 *   <li>複合ユニーク制約: {@code @UniqueConstraint}</li>
 * </ul>
 *
 * <p>FK/UniqueConstraint情報は{@link GspRelationSupport}経由で
 * JDBC DatabaseMetaDataから直接取得する（jOOQ APIのFK取得問題を回避）。</p>
 *
 * <p>Mojoパラメータは{@link GspEntityGenerationConfig.EntityGenParams}経由で
 * ThreadLocalから取得する。</p>
 */
public class GspJpaEntityGenerator extends JavaGenerator {

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

        // FK/UK事前計算（GspRelationSupport経由でJDBC metadataから取得）
        String tableName = table.getOutputName();
        List<GspRelationSupport.ForeignKeyDef> forwardFKs = GspRelationSupport.getForeignKeys(tableName);
        Set<String> fkColumnNames = collectFKColumnNames(forwardFKs);
        List<GspRelationSupport.ForeignKeyDef> inverseFKs = GspRelationSupport.getInverseForeignKeys(tableName);
        Map<String, List<String>> uniqueConstraints = GspRelationSupport.getUniqueConstraints(tableName);

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
        out.println("import jakarta.persistence.Column;");
        out.println("import jakarta.persistence.Entity;");
        out.println("import jakarta.persistence.Table;");

        boolean hasId = hasPrimaryKey(table);
        if (hasId) {
            out.println("import jakarta.persistence.Id;");
        }

        boolean hasIdentity = hasIdentityColumn(table);
        if (hasId && !isViewTable) {
            out.println("import jakarta.persistence.GeneratedValue;");
            out.println("import jakarta.persistence.GenerationType;");
            if (!hasIdentity) {
                out.println("import jakarta.persistence.SequenceGenerator;");
            }
        }

        if (hasVersionColumn) {
            out.println("import jakarta.persistence.Version;");
        }

        // FK関連import
        boolean hasFKs = !forwardFKs.isEmpty();
        boolean hasInverseFKs = !inverseFKs.isEmpty();

        if (hasFKs) {
            out.println("import jakarta.persistence.JoinColumn;");
            if (hasCompositeForeignKey(forwardFKs)) {
                out.println("import jakarta.persistence.JoinColumns;");
            }
            out.println("import jakarta.persistence.ManyToOne;");
        }
        if (hasInverseFKs) {
            out.println("import jakarta.persistence.OneToMany;");
        }
        if (!uniqueConstraints.isEmpty()) {
            out.println("import jakarta.persistence.UniqueConstraint;");
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

        // @Table（UniqueConstraint対応）
        SchemaDefinition schema = table.getSchema();
        String schemaName = schema != null ? schema.getOutputName() : null;

        generateTableAnnotation(out, schemaName, tableName, uniqueConstraints);

        // クラス宣言
        out.println("public class %s implements Serializable {", className);
        out.println();
        out.println("    private static final long serialVersionUID = 1L;");

        // アクセサ生成用のフィールド情報を収集
        List<String> fieldNames = new ArrayList<>();
        List<String> fieldSimpleTypes = new ArrayList<>();

        // フィールド
        String accessModifier = useAccessor ? "private" : "public";

        for (ColumnDefinition column : columns) {
            out.println();
            String fieldName = getStrategy().getJavaMemberName(column, Mode.POJO);
            String javaType = getJavaType(column);
            boolean isPk = isPrimaryKey(table, column);
            boolean isFkColumn = fkColumnNames.contains(column.getName());

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
                        out.println("    @GeneratedValue(generator = \"%s\", strategy = GenerationType.AUTO)", seqName);
                        out.println("    @SequenceGenerator(name = \"%s\", sequenceName = \"%s\", initialValue = 1, allocationSize = %d)", seqName, seqName, allocationSize);
                    }
                }
            }

            // @Version
            if (versionPattern != null && !versionPattern.isEmpty()
                    && column.getOutputName().matches(versionPattern)) {
                out.println("    @Version");
            }

            // @Column
            DataTypeDefinition type = column.getType();
            boolean nullable = !column.isIdentity() && type.isNullable();
            int length = type.getLength();
            int precision = type.getPrecision();
            int scale = type.getScale();

            StringBuilder colAnnotation = new StringBuilder("    @Column(");
            colAnnotation.append("name = \"").append(column.getOutputName()).append("\"");

            if (length > 0 && isStringType(javaType)) {
                colAnnotation.append(", length = ").append(length);
            }
            if (precision > 0 && isNumericType(javaType)) {
                colAnnotation.append(", precision = ").append(precision);
                if (scale > 0) {
                    colAnnotation.append(", scale = ").append(scale);
                }
            }
            colAnnotation.append(", nullable = ").append(nullable);
            colAnnotation.append(", unique = ").append(isPk && getPrimaryKeyColumnCount(table) == 1);

            // FKカラムはinsertable/updatable = false
            if (isFkColumn) {
                colAnnotation.append(", insertable = false, updatable = false");
            }

            colAnnotation.append(")");
            out.println(colAnnotation.toString());

            // フィールド宣言
            String simpleType = getSimpleJavaType(javaType);
            out.println("    %s %s %s;", accessModifier, simpleType, fieldName);

            // アクセサ用にフィールド情報を保存
            if (useAccessor) {
                fieldNames.add(fieldName);
                fieldSimpleTypes.add(simpleType);
            }
        }

        // @ManyToOne関連フィールド
        List<String> manyToOneFieldNames = new ArrayList<>();
        List<String> manyToOneTypes = new ArrayList<>();
        for (GspRelationSupport.ForeignKeyDef fk : forwardFKs) {
            String refTableName = fk.getPkTableName();
            String refClassName = toPascalCase(refTableName);
            String mtoFieldName = toFieldName(refClassName);

            generateManyToOneField(out, fk, refClassName, accessModifier);
            manyToOneFieldNames.add(mtoFieldName);
            manyToOneTypes.add(refClassName);
        }

        // @OneToMany関連フィールド
        List<String> oneToManyFieldNames = new ArrayList<>();
        List<String> oneToManyTypes = new ArrayList<>();
        for (GspRelationSupport.ForeignKeyDef inverseFk : inverseFKs) {
            String refTableName = inverseFk.getFkTableName();
            String refClassName = toPascalCase(refTableName);
            String currentClassName = toPascalCase(tableName);

            String otmFieldName = generateOneToManyField(out, refClassName, currentClassName, accessModifier);
            oneToManyFieldNames.add(otmFieldName);
            oneToManyTypes.add("java.util.List<" + refClassName + ">");
        }

        // アクセサ（getter/setter）の生成
        if (useAccessor) {
            for (int i = 0; i < fieldNames.size(); i++) {
                generateAccessor(out, fieldNames.get(i), fieldSimpleTypes.get(i));
            }
            for (int i = 0; i < manyToOneFieldNames.size(); i++) {
                generateAccessor(out, manyToOneFieldNames.get(i), manyToOneTypes.get(i));
            }
            for (int i = 0; i < oneToManyFieldNames.size(); i++) {
                generateAccessor(out, oneToManyFieldNames.get(i), oneToManyTypes.get(i));
            }
        }

        // クラスの閉じ
        out.println("}");
    }

    /**
     * @Tableアノテーションを生成する（UniqueConstraint対応）。
     */
    private void generateTableAnnotation(JavaWriter out, String schemaName, String tableName,
                                          Map<String, List<String>> uniqueConstraints) {
        boolean hasSchema = schemaName != null && !schemaName.isEmpty() && !"PUBLIC".equals(schemaName);
        boolean hasUniqueConstraints = !uniqueConstraints.isEmpty();

        StringBuilder sb = new StringBuilder("@Table(");
        if (hasSchema) {
            sb.append("schema = \"").append(schemaName).append("\", ");
        }
        sb.append("name = \"").append(tableName).append("\"");

        if (hasUniqueConstraints) {
            sb.append(", uniqueConstraints = {");
            int i = 0;
            for (Map.Entry<String, List<String>> entry : uniqueConstraints.entrySet()) {
                if (i > 0) sb.append(", ");
                sb.append("@UniqueConstraint(columnNames = {");
                List<String> colNames = entry.getValue();
                for (int j = 0; j < colNames.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append("\"").append(colNames.get(j)).append("\"");
                }
                sb.append("})");
                i++;
            }
            sb.append("}");
        }

        sb.append(")");
        out.println(sb.toString());
    }

    /**
     * @ManyToOne + @JoinColumn フィールドを生成する。
     */
    private void generateManyToOneField(JavaWriter out, GspRelationSupport.ForeignKeyDef fk,
                                          String refClassName, String accessModifier) {
        String fieldName = toFieldName(refClassName);
        List<GspRelationSupport.ForeignKeyColumn> fkCols = fk.getColumns();

        out.println();
        out.println("    /** %s関連プロパティ */", fieldName);
        out.println("    @ManyToOne");

        if (fkCols.size() == 1) {
            GspRelationSupport.ForeignKeyColumn col = fkCols.get(0);
            out.println("    @JoinColumn(name = \"%s\", referencedColumnName = \"%s\")",
                col.getFkColumnName(), col.getPkColumnName());
        } else {
            // 複合FK
            out.println("    @JoinColumns({");
            for (int i = 0; i < fkCols.size(); i++) {
                GspRelationSupport.ForeignKeyColumn col = fkCols.get(i);
                String comma = (i < fkCols.size() - 1) ? "," : "";
                out.println("        @JoinColumn(name = \"%s\", referencedColumnName = \"%s\")%s",
                    col.getFkColumnName(), col.getPkColumnName(), comma);
            }
            out.println("    })");
        }

        out.println("    %s %s %s;", accessModifier, refClassName, fieldName);
    }

    /**
     * @OneToMany フィールドを生成する。
     *
     * @return 生成されたフィールド名
     */
    private String generateOneToManyField(JavaWriter out, String referencingClassName,
                                            String currentClassName, String accessModifier) {
        // mappedBy = 参照元テーブルでの@ManyToOneフィールド名
        String mappedBy = toFieldName(currentClassName);
        String listFieldName = toFieldName(referencingClassName) + "List";

        out.println();
        out.println("    /** %s関連プロパティ */", listFieldName);
        out.println("    @OneToMany(mappedBy = \"%s\")", mappedBy);
        out.println("    %s java.util.List<%s> %s;", accessModifier, referencingClassName, listFieldName);

        return listFieldName;
    }

    /**
     * getter/setterを生成する。
     */
    private void generateAccessor(JavaWriter out, String fieldName, String type) {
        String capitalizedName = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String getterPrefix = "boolean".equals(type) ? "is" : "get";

        out.println();
        out.println("    public %s %s%s() {", type, getterPrefix, capitalizedName);
        out.println("        return %s;", fieldName);
        out.println("    }");
        out.println();
        out.println("    public void set%s(%s %s) {", capitalizedName, type, fieldName);
        out.println("        this.%s = %s;", fieldName, fieldName);
        out.println("    }");
    }

    /**
     * FKカラム名のセットを収集する。
     */
    private Set<String> collectFKColumnNames(List<GspRelationSupport.ForeignKeyDef> fks) {
        Set<String> names = new HashSet<>();
        for (GspRelationSupport.ForeignKeyDef fk : fks) {
            for (GspRelationSupport.ForeignKeyColumn col : fk.getColumns()) {
                names.add(col.getFkColumnName());
            }
        }
        return names;
    }

    /**
     * 複合FKが存在するかを判定する。
     */
    private boolean hasCompositeForeignKey(List<GspRelationSupport.ForeignKeyDef> fks) {
        for (GspRelationSupport.ForeignKeyDef fk : fks) {
            if (fk.getColumns().size() > 1) return true;
        }
        return false;
    }

    /**
     * クラス名をフィールド名に変換する（先頭小文字化）。
     */
    private String toFieldName(String className) {
        return Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    /**
     * テーブル名（UPPER_SNAKE_CASE）をPascalCaseに変換する。
     * 例: TEST_TBL1 → TestTbl1, TYPETEST → Typetest
     */
    private String toPascalCase(String tableName) {
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : tableName.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    sb.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            }
        }
        return sb.toString();
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
     * PKカラム数を返す。
     */
    private int getPrimaryKeyColumnCount(TableDefinition table) {
        UniqueKeyDefinition pk = table.getPrimaryKey();
        if (pk != null) return pk.getKeyColumns().size();
        return getInferredOrActualPKs(table).size();
    }

    /**
     * カラムがPKに含まれるかを判定する。
     * VIEWの場合は{@link GspViewSupport}の推定PKを確認する。
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
     * VIEWの推定PKカラム名リストを返す。VIEWでない場合は空リスト。
     */
    private List<String> getInferredOrActualPKs(TableDefinition table) {
        if (GspViewSupport.isView(table.getOutputName())) {
            return GspViewSupport.getInferredPrimaryKeys(table.getOutputName());
        }
        return Collections.emptyList();
    }

    /**
     * IDENTITY（AUTO_INCREMENT）カラムが存在するかを判定する。
     */
    private boolean hasIdentityColumn(TableDefinition table) {
        return table.getColumns().stream().anyMatch(ColumnDefinition::isIdentity);
    }

    /**
     * カラムのJava型名を取得する。
     * ForcedType（userType）が設定されている場合はそちらを優先する。
     */
    private String getJavaType(ColumnDefinition column) {
        DataTypeDefinition type = column.getType();
        String userType = type.getUserType();
        if (userType != null && !userType.isEmpty()) {
            return userType;
        }
        boolean useJSR310 = GspEntityGenerationConfig.getParams().isUseJSR310();
        return GspColumnTypeMapper.getJavaType(type, useJSR310);
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

    private boolean isStringType(String javaType) {
        return "java.lang.String".equals(javaType);
    }

    private boolean isNumericType(String javaType) {
        return "java.math.BigDecimal".equals(javaType) || "java.lang.Integer".equals(javaType)
            || "java.lang.Long".equals(javaType) || "java.lang.Short".equals(javaType)
            || "java.lang.Float".equals(javaType) || "java.lang.Double".equals(javaType);
    }
}
