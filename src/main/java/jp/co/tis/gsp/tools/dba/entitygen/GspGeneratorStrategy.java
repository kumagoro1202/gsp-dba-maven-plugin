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

import org.jooq.codegen.DefaultGeneratorStrategy;
import org.jooq.meta.Definition;

/**
 * gsp-dba-maven-plugin互換の命名戦略。
 * S2JDBC-GenのPersistenceConventionを代替する。
 *
 * テーブル名→PascalCase、カラム名→camelCaseへの変換を行う。
 */
public class GspGeneratorStrategy extends DefaultGeneratorStrategy {

    @Override
    public String getJavaClassName(Definition definition, Mode mode) {
        if (mode == Mode.POJO) {
            return toPascalCase(definition.getOutputName());
        }
        return super.getJavaClassName(definition, mode);
    }

    @Override
    public String getJavaPackageName(Definition definition, Mode mode) {
        // POJOをルートパッケージ直下に配置する（デフォルトの".pojos"サブパッケージを使わない）
        if (mode == Mode.POJO) {
            return getTargetPackage();
        }
        return super.getJavaPackageName(definition, mode);
    }

    @Override
    public String getJavaMemberName(Definition definition, Mode mode) {
        return toCamelCase(definition.getOutputName());
    }

    /**
     * スネークケースからPascalCaseへ変換する。
     * 例: TEST_TBL1 → TestTbl1, ORDER_DETAIL → OrderDetail
     */
    static String toPascalCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(Character.toLowerCase(c));
                nextUpper = false;
            }
        }
        return sb.toString();
    }

    /**
     * スネークケースからcamelCaseへ変換する。
     * 例: TEST_TBL1_ID → testTbl1Id, TEST_NAME → testName
     */
    static String toCamelCase(String name) {
        String pascal = toPascalCase(name);
        if (pascal == null || pascal.isEmpty()) {
            return pascal;
        }
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }
}
