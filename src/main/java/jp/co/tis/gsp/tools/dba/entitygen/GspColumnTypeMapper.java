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

import org.jooq.meta.DataTypeDefinition;
import org.jooq.meta.jaxb.ForcedType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * DB型からJava型へのマッピングを管理するクラス。
 *
 * <p>S2JDBC-GenのExtendedXxxGenDialect columnTypeMapを
 * {@code Map<String, Function<DataTypeDefinition, String>>} ベースの変換に置換する。
 * DB固有の型マッピングを{@link #addCustomMapping}で追加可能。</p>
 */
public class GspColumnTypeMapper {

    /** DB型名（大文字）からJava型FQCNへの変換マップ */
    private static final Map<String, Function<DataTypeDefinition, String>> TYPE_MAP = new LinkedHashMap<>();

    static {
        // 整数型
        Function<DataTypeDefinition, String> toInteger = type -> "java.lang.Integer";
        TYPE_MAP.put("INTEGER", toInteger);
        TYPE_MAP.put("INT", toInteger);
        TYPE_MAP.put("INT4", toInteger);

        Function<DataTypeDefinition, String> toLong = type -> "java.lang.Long";
        TYPE_MAP.put("BIGINT", toLong);
        TYPE_MAP.put("INT8", toLong);

        Function<DataTypeDefinition, String> toShort = type -> "java.lang.Short";
        TYPE_MAP.put("SMALLINT", toShort);
        TYPE_MAP.put("INT2", toShort);
        TYPE_MAP.put("TINYINT", toShort);

        // 文字列型
        Function<DataTypeDefinition, String> toString = type -> "java.lang.String";
        TYPE_MAP.put("VARCHAR", toString);
        TYPE_MAP.put("CHARACTER VARYING", toString);
        TYPE_MAP.put("NVARCHAR", toString);
        TYPE_MAP.put("TEXT", toString);
        TYPE_MAP.put("CLOB", toString);
        TYPE_MAP.put("CHAR", toString);
        TYPE_MAP.put("CHARACTER", toString);

        // 真偽値型
        Function<DataTypeDefinition, String> toBoolean = type -> "boolean";
        TYPE_MAP.put("BOOLEAN", toBoolean);
        TYPE_MAP.put("BOOL", toBoolean);
        TYPE_MAP.put("BIT", toBoolean);

        // DECIMAL/NUMERIC（精度・スケールに応じた変換）
        Function<DataTypeDefinition, String> toDecimal = type -> {
            int precision = type.getPrecision();
            int scale = type.getScale();
            if (scale == 0 && precision == 1) return "boolean";
            if (scale == 0 && precision < 5) return "java.lang.Short";
            if (scale == 0 && precision < 10) return "java.lang.Integer";
            if (scale == 0 && precision < 19) return "java.lang.Long";
            return "java.math.BigDecimal";
        };
        TYPE_MAP.put("DECIMAL", toDecimal);
        TYPE_MAP.put("NUMERIC", toDecimal);

        // 浮動小数点型
        Function<DataTypeDefinition, String> toFloat = type -> "java.lang.Float";
        TYPE_MAP.put("REAL", toFloat);
        TYPE_MAP.put("FLOAT4", toFloat);

        Function<DataTypeDefinition, String> toDouble = type -> "java.lang.Double";
        TYPE_MAP.put("DOUBLE", toDouble);
        TYPE_MAP.put("DOUBLE PRECISION", toDouble);
        TYPE_MAP.put("FLOAT8", toDouble);
        TYPE_MAP.put("FLOAT", toDouble);

        // 日付・時刻型
        TYPE_MAP.put("DATE", type -> "java.sql.Date");
        TYPE_MAP.put("TIME", type -> "java.sql.Time");

        Function<DataTypeDefinition, String> toTimestamp = type -> "java.sql.Timestamp";
        TYPE_MAP.put("TIMESTAMP", toTimestamp);
        TYPE_MAP.put("DATETIME", toTimestamp);
        TYPE_MAP.put("SMALLDATETIME", toTimestamp);

        // バイナリ型
        Function<DataTypeDefinition, String> toByteArray = type -> "byte[]";
        TYPE_MAP.put("BLOB", toByteArray);
        TYPE_MAP.put("BINARY", toByteArray);
        TYPE_MAP.put("VARBINARY", toByteArray);
        TYPE_MAP.put("BYTEA", toByteArray);
    }

    private GspColumnTypeMapper() {
    }

    /**
     * DataTypeDefinitionからJava型のFQCNを解決する。
     *
     * <p>型マッピングマップに一致するエントリがあればその変換を適用し、
     * なければデフォルトの変換（TIMESTAMP接頭辞→Timestamp、その他→String）を行う。</p>
     *
     * @param type データ型定義
     * @return Java型のFQCN
     */
    public static String getJavaType(DataTypeDefinition type) {
        String typeName = type.getType().toUpperCase();

        Function<DataTypeDefinition, String> mapper = TYPE_MAP.get(typeName);
        if (mapper != null) {
            return mapper.apply(type);
        }

        // デフォルト: TIMESTAMP接頭辞の変種（TIMESTAMP WITH TIME ZONEなど）を処理
        if (typeName.startsWith("TIMESTAMP")) {
            return "java.sql.Timestamp";
        }
        return "java.lang.String";
    }

    /**
     * DB固有の型マッピングを追加する。
     *
     * <p>特定のDB（Oracle NUMBER型等）向けのカスタム変換を登録できる。
     * 既存のマッピングを上書きすることも可能。</p>
     *
     * @param sqlTypeName SQL型名（大文字で指定すること）
     * @param mapper DataTypeDefinitionからJava型FQCNへの変換関数
     */
    public static void addCustomMapping(String sqlTypeName, Function<DataTypeDefinition, String> mapper) {
        TYPE_MAP.put(sqlTypeName.toUpperCase(), mapper);
    }

    /**
     * JSR310対応の型マッピング（ForcedType）を生成する。
     * useJSR310=trueの場合、日付・時刻型をjava.time.*にマッピングする。
     *
     * @param useJSR310 JSR310を使用するか
     * @return ForcedType設定のリスト
     */
    public static List<ForcedType> createForcedTypes(boolean useJSR310) {
        List<ForcedType> types = new ArrayList<>();

        if (useJSR310) {
            types.add(new ForcedType()
                .withUserType("java.time.LocalDate")
                .withIncludeTypes("DATE")
            );
            types.add(new ForcedType()
                .withUserType("java.time.LocalDateTime")
                .withIncludeTypes("TIMESTAMP.*|DATETIME|SMALLDATETIME")
            );
            types.add(new ForcedType()
                .withUserType("java.time.LocalTime")
                .withIncludeTypes("TIME")
            );
        }

        return types;
    }
}
