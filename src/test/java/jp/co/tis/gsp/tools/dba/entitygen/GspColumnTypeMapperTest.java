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
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GspColumnTypeMapperのテスト。
 * DB型名からJava型FQCNへの変換と、ForcedType生成を検証する。
 */
public class GspColumnTypeMapperTest {

    /**
     * DataTypeDefinitionのモックを作成するヘルパー。
     */
    private DataTypeDefinition mockType(String typeName, int precision, int scale) {
        DataTypeDefinition type = mock(DataTypeDefinition.class);
        when(type.getType()).thenReturn(typeName);
        when(type.getPrecision()).thenReturn(precision);
        when(type.getScale()).thenReturn(scale);
        return type;
    }

    private DataTypeDefinition mockType(String typeName) {
        return mockType(typeName, 0, 0);
    }

    // ========== 整数型テスト ==========

    @Test
    public void testIntegerTypes() {
        assertEquals("java.lang.Integer", GspColumnTypeMapper.getJavaType(mockType("INTEGER")));
        assertEquals("java.lang.Integer", GspColumnTypeMapper.getJavaType(mockType("INT")));
        assertEquals("java.lang.Integer", GspColumnTypeMapper.getJavaType(mockType("INT4")));
    }

    @Test
    public void testBigintTypes() {
        assertEquals("java.lang.Long", GspColumnTypeMapper.getJavaType(mockType("BIGINT")));
        assertEquals("java.lang.Long", GspColumnTypeMapper.getJavaType(mockType("INT8")));
    }

    @Test
    public void testSmallintTypes() {
        assertEquals("java.lang.Short", GspColumnTypeMapper.getJavaType(mockType("SMALLINT")));
        assertEquals("java.lang.Short", GspColumnTypeMapper.getJavaType(mockType("INT2")));
        assertEquals("java.lang.Short", GspColumnTypeMapper.getJavaType(mockType("TINYINT")));
    }

    // ========== 文字列型テスト ==========

    @Test
    public void testStringTypes() {
        assertEquals("java.lang.String", GspColumnTypeMapper.getJavaType(mockType("VARCHAR")));
        assertEquals("java.lang.String", GspColumnTypeMapper.getJavaType(mockType("CHARACTER VARYING")));
        assertEquals("java.lang.String", GspColumnTypeMapper.getJavaType(mockType("NVARCHAR")));
        assertEquals("java.lang.String", GspColumnTypeMapper.getJavaType(mockType("TEXT")));
        assertEquals("java.lang.String", GspColumnTypeMapper.getJavaType(mockType("CLOB")));
        assertEquals("java.lang.String", GspColumnTypeMapper.getJavaType(mockType("CHAR")));
        assertEquals("java.lang.String", GspColumnTypeMapper.getJavaType(mockType("CHARACTER")));
    }

    // ========== 真偽値型テスト ==========

    @Test
    public void testBooleanTypes() {
        assertEquals("boolean", GspColumnTypeMapper.getJavaType(mockType("BOOLEAN")));
        assertEquals("boolean", GspColumnTypeMapper.getJavaType(mockType("BOOL")));
        assertEquals("boolean", GspColumnTypeMapper.getJavaType(mockType("BIT")));
    }

    // ========== DECIMAL/NUMERIC精度テスト ==========

    @Test
    public void testDecimalPrecision1() {
        assertEquals("boolean", GspColumnTypeMapper.getJavaType(mockType("DECIMAL", 1, 0)));
        assertEquals("boolean", GspColumnTypeMapper.getJavaType(mockType("NUMERIC", 1, 0)));
    }

    @Test
    public void testDecimalPrecisionShort() {
        assertEquals("java.lang.Short", GspColumnTypeMapper.getJavaType(mockType("DECIMAL", 2, 0)));
        assertEquals("java.lang.Short", GspColumnTypeMapper.getJavaType(mockType("DECIMAL", 4, 0)));
    }

    @Test
    public void testDecimalPrecisionInteger() {
        assertEquals("java.lang.Integer", GspColumnTypeMapper.getJavaType(mockType("DECIMAL", 5, 0)));
        assertEquals("java.lang.Integer", GspColumnTypeMapper.getJavaType(mockType("DECIMAL", 9, 0)));
    }

    @Test
    public void testDecimalPrecisionLong() {
        assertEquals("java.lang.Long", GspColumnTypeMapper.getJavaType(mockType("DECIMAL", 10, 0)));
        assertEquals("java.lang.Long", GspColumnTypeMapper.getJavaType(mockType("DECIMAL", 18, 0)));
    }

    @Test
    public void testDecimalPrecisionBigDecimal() {
        assertEquals("java.math.BigDecimal", GspColumnTypeMapper.getJavaType(mockType("DECIMAL", 19, 0)));
        assertEquals("java.math.BigDecimal", GspColumnTypeMapper.getJavaType(mockType("DECIMAL", 10, 2)));
    }

    // ========== 浮動小数点型テスト ==========

    @Test
    public void testFloatTypes() {
        assertEquals("java.lang.Float", GspColumnTypeMapper.getJavaType(mockType("REAL")));
        assertEquals("java.lang.Float", GspColumnTypeMapper.getJavaType(mockType("FLOAT4")));
    }

    @Test
    public void testDoubleTypes() {
        assertEquals("java.lang.Double", GspColumnTypeMapper.getJavaType(mockType("DOUBLE")));
        assertEquals("java.lang.Double", GspColumnTypeMapper.getJavaType(mockType("DOUBLE PRECISION")));
        assertEquals("java.lang.Double", GspColumnTypeMapper.getJavaType(mockType("FLOAT8")));
        assertEquals("java.lang.Double", GspColumnTypeMapper.getJavaType(mockType("FLOAT")));
    }

    // ========== 日付・時刻型テスト ==========

    @Test
    public void testDateType() {
        assertEquals("java.sql.Date", GspColumnTypeMapper.getJavaType(mockType("DATE")));
    }

    @Test
    public void testTimeType() {
        assertEquals("java.sql.Time", GspColumnTypeMapper.getJavaType(mockType("TIME")));
    }

    @Test
    public void testTimestampTypes() {
        assertEquals("java.sql.Timestamp", GspColumnTypeMapper.getJavaType(mockType("TIMESTAMP")));
        assertEquals("java.sql.Timestamp", GspColumnTypeMapper.getJavaType(mockType("DATETIME")));
        assertEquals("java.sql.Timestamp", GspColumnTypeMapper.getJavaType(mockType("SMALLDATETIME")));
    }

    @Test
    public void testTimestampWithTimeZone() {
        assertEquals("java.sql.Timestamp", GspColumnTypeMapper.getJavaType(mockType("TIMESTAMP WITH TIME ZONE")));
        assertEquals("java.sql.Timestamp", GspColumnTypeMapper.getJavaType(mockType("TIMESTAMP WITHOUT TIME ZONE")));
    }

    // ========== バイナリ型テスト ==========

    @Test
    public void testBinaryTypes() {
        assertEquals("byte[]", GspColumnTypeMapper.getJavaType(mockType("BLOB")));
        assertEquals("byte[]", GspColumnTypeMapper.getJavaType(mockType("BINARY")));
        assertEquals("byte[]", GspColumnTypeMapper.getJavaType(mockType("VARBINARY")));
        assertEquals("byte[]", GspColumnTypeMapper.getJavaType(mockType("BYTEA")));
    }

    // ========== デフォルト（未知の型）テスト ==========

    @Test
    public void testUnknownType() {
        assertEquals("java.lang.String", GspColumnTypeMapper.getJavaType(mockType("UNKNOWN_TYPE")));
    }

    // ========== 大文字小文字テスト ==========

    @Test
    public void testCaseInsensitivity() {
        assertEquals("java.lang.Integer", GspColumnTypeMapper.getJavaType(mockType("integer")));
        assertEquals("java.lang.String", GspColumnTypeMapper.getJavaType(mockType("varchar")));
        assertEquals("java.sql.Timestamp", GspColumnTypeMapper.getJavaType(mockType("timestamp")));
    }

    // ========== カスタムマッピングテスト ==========

    @Test
    public void testCustomMapping() {
        // Oracle NUMBER型のカスタムマッピングを追加
        GspColumnTypeMapper.addCustomMapping("NUMBER", type -> {
            int precision = type.getPrecision();
            int scale = type.getScale();
            if (scale > 0) return "java.math.BigDecimal";
            if (precision <= 0) return "java.math.BigDecimal";
            if (precision < 10) return "java.lang.Integer";
            if (precision < 19) return "java.lang.Long";
            return "java.math.BigDecimal";
        });

        assertEquals("java.lang.Integer", GspColumnTypeMapper.getJavaType(mockType("NUMBER", 5, 0)));
        assertEquals("java.lang.Long", GspColumnTypeMapper.getJavaType(mockType("NUMBER", 10, 0)));
        assertEquals("java.math.BigDecimal", GspColumnTypeMapper.getJavaType(mockType("NUMBER", 10, 2)));
    }

    // ========== ForcedType生成テスト ==========

    @Test
    public void testCreateForcedTypesWithJSR310() {
        List<ForcedType> types = GspColumnTypeMapper.createForcedTypes(true);
        assertEquals("JSR310有効時は3件のForcedType", 3, types.size());

        assertTrue("LocalDate含む", types.stream()
            .anyMatch(t -> "java.time.LocalDate".equals(t.getUserType())));
        assertTrue("LocalDateTime含む", types.stream()
            .anyMatch(t -> "java.time.LocalDateTime".equals(t.getUserType())));
        assertTrue("LocalTime含む", types.stream()
            .anyMatch(t -> "java.time.LocalTime".equals(t.getUserType())));
    }

    @Test
    public void testCreateForcedTypesWithoutJSR310() {
        List<ForcedType> types = GspColumnTypeMapper.createForcedTypes(false);
        assertTrue("JSR310無効時はForcedTypeなし", types.isEmpty());
    }
}
