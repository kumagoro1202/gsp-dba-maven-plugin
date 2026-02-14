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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * GspGeneratorStrategyの命名変換のテスト。
 */
public class GspGeneratorStrategyTest {

    @Test
    public void testToPascalCase() {
        assertEquals("TestTbl1", GspGeneratorStrategy.toPascalCase("TEST_TBL1"));
        assertEquals("OrderDetail", GspGeneratorStrategy.toPascalCase("ORDER_DETAIL"));
        assertEquals("IndexTest1", GspGeneratorStrategy.toPascalCase("INDEX_TEST1"));
        assertEquals("View1", GspGeneratorStrategy.toPascalCase("VIEW1"));
        assertEquals("Typetest", GspGeneratorStrategy.toPascalCase("TYPETEST"));
        assertEquals("MstOrg", GspGeneratorStrategy.toPascalCase("MST_ORG"));
        assertEquals("MstOrgName", GspGeneratorStrategy.toPascalCase("MST_ORG_NAME"));
    }

    @Test
    public void testToCamelCase() {
        assertEquals("testTbl1Id", GspGeneratorStrategy.toCamelCase("TEST_TBL1_ID"));
        assertEquals("testName", GspGeneratorStrategy.toCamelCase("TEST_NAME"));
        assertEquals("colVarchar", GspGeneratorStrategy.toCamelCase("COL_VARCHAR"));
        assertEquals("versionNo", GspGeneratorStrategy.toCamelCase("VERSION_NO"));
        assertEquals("typeId", GspGeneratorStrategy.toCamelCase("TYPE_ID"));
    }

    @Test
    public void testEdgeCases() {
        assertEquals("", GspGeneratorStrategy.toPascalCase(""));
        assertEquals("", GspGeneratorStrategy.toCamelCase(""));
        assertNull(GspGeneratorStrategy.toPascalCase(null));
        assertNull(GspGeneratorStrategy.toCamelCase(null));
        assertEquals("A", GspGeneratorStrategy.toPascalCase("A"));
        assertEquals("a", GspGeneratorStrategy.toCamelCase("A"));
    }
}
