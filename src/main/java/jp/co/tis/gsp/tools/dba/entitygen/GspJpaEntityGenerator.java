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
import java.util.List;

/**
 * jOOQ JavaGeneratorを拡張し、gsp-dba-maven-plugin互換のJPA Entityを生成する。
 *
 * 生成されるEntityの特徴:
 * <ul>
 *   <li>Jakarta Persistence API（jakarta.persistence.*）使用</li>
 *   <li>{@code @Generated("GSP")} アノテーション付与</li>
 *   <li>{@code @Entity}, {@code @Table}, {@code @Column}, {@code @Id} 等</li>
 *   <li>{@link Serializable}実装</li>
 *   <li>publicフィールド（gspデフォルト）</li>
 * </ul>
 */
public class GspJpaEntityGenerator extends JavaGenerator {

    @Override
    protected void generatePojo(TableDefinition table, JavaWriter out) {
        String className = getStrategy().getJavaClassName(table, Mode.POJO);
        String packageName = getStrategy().getJavaPackageName(table, Mode.POJO);
        List<ColumnDefinition> columns = table.getColumns();

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
        boolean hasSequence = false;
        if (hasId) {
            out.println("import jakarta.persistence.GeneratedValue;");
            out.println("import jakarta.persistence.GenerationType;");
            if (!hasIdentity) {
                out.println("import jakarta.persistence.SequenceGenerator;");
                hasSequence = true;
            }
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

        // フィールド
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

                // @GeneratedValue
                if (hasIdentity) {
                    out.println("    @GeneratedValue(strategy = GenerationType.IDENTITY)");
                } else {
                    String seqName = column.getOutputName() + "_SEQ";
                    if (schemaName != null && !schemaName.isEmpty() && !"PUBLIC".equals(schemaName)) {
                        seqName = schemaName + "." + seqName;
                    }
                    out.println("    @GeneratedValue(generator = \"%s\", strategy = GenerationType.AUTO)", seqName);
                    out.println("    @SequenceGenerator(name = \"%s\", sequenceName = \"%s\", initialValue = 1, allocationSize = 1)", seqName, seqName);
                }
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
            colAnnotation.append(")");
            out.println(colAnnotation.toString());

            // フィールド宣言
            out.println("    public %s %s;", getSimpleJavaType(javaType), fieldName);
        }

        // クラスの閉じ
        out.println("}");
    }

    /**
     * テーブルにPKがあるかどうかを判定する。
     */
    private boolean hasPrimaryKey(TableDefinition table) {
        UniqueKeyDefinition pk = table.getPrimaryKey();
        return pk != null && !pk.getKeyColumns().isEmpty();
    }

    /**
     * PKカラム数を返す。
     */
    private int getPrimaryKeyColumnCount(TableDefinition table) {
        UniqueKeyDefinition pk = table.getPrimaryKey();
        return pk != null ? pk.getKeyColumns().size() : 0;
    }

    /**
     * カラムがPKに含まれるかを判定する。
     */
    private boolean isPrimaryKey(TableDefinition table, ColumnDefinition column) {
        UniqueKeyDefinition pk = table.getPrimaryKey();
        if (pk == null) return false;
        return pk.getKeyColumns().stream()
            .anyMatch(c -> c.getName().equals(column.getName()));
    }

    /**
     * IDENTITY（AUTO_INCREMENT）カラムが存在するかを判定する。
     */
    private boolean hasIdentityColumn(TableDefinition table) {
        return table.getColumns().stream().anyMatch(ColumnDefinition::isIdentity);
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
        return getJavaType(type);
    }

    /**
     * DataTypeDefinitionからJava型名を取得する。
     */
    private String getJavaType(DataTypeDefinition type) {
        String typeName = type.getType().toUpperCase();
        int precision = type.getPrecision();
        int scale = type.getScale();

        return switch (typeName) {
            case "INTEGER", "INT", "INT4" -> "java.lang.Integer";
            case "BIGINT", "INT8" -> "java.lang.Long";
            case "SMALLINT", "INT2", "TINYINT" -> "java.lang.Short";
            case "VARCHAR", "CHARACTER VARYING", "NVARCHAR", "TEXT", "CLOB", "CHAR", "CHARACTER" -> "java.lang.String";
            case "BOOLEAN", "BOOL", "BIT" -> "boolean";
            case "DECIMAL", "NUMERIC" -> {
                if (scale == 0 && precision == 1) yield "boolean";
                if (scale == 0 && precision < 5) yield "java.lang.Short";
                if (scale == 0 && precision < 10) yield "java.lang.Integer";
                if (scale == 0 && precision < 19) yield "java.lang.Long";
                yield "java.math.BigDecimal";
            }
            case "REAL", "FLOAT4" -> "java.lang.Float";
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8", "FLOAT" -> "java.lang.Double";
            case "DATE" -> "java.sql.Date";
            case "TIME" -> "java.sql.Time";
            case "TIMESTAMP", "DATETIME", "SMALLDATETIME" -> "java.sql.Timestamp";
            case "BLOB", "BINARY", "VARBINARY", "BYTEA" -> "byte[]";
            default -> {
                if (typeName.startsWith("TIMESTAMP")) yield "java.sql.Timestamp";
                yield "java.lang.String";
            }
        };
    }

    /**
     * import文に必要な型を追加する。
     */
    private void addTypeImports(JavaWriter out, List<ColumnDefinition> columns) {
        boolean needsSqlDate = false;
        boolean needsSqlTime = false;
        boolean needsSqlTimestamp = false;
        boolean needsBigDecimal = false;

        for (ColumnDefinition column : columns) {
            String javaType = getJavaType(column);
            if ("java.sql.Date".equals(javaType)) needsSqlDate = true;
            if ("java.sql.Time".equals(javaType)) needsSqlTime = true;
            if ("java.sql.Timestamp".equals(javaType)) needsSqlTimestamp = true;
            if ("java.math.BigDecimal".equals(javaType)) needsBigDecimal = true;
        }

        if (needsBigDecimal) out.println("import java.math.BigDecimal;");
        if (needsSqlDate) out.println("import java.sql.Date;");
        if (needsSqlTime) out.println("import java.sql.Time;");
        if (needsSqlTimestamp) out.println("import java.sql.Timestamp;");
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
