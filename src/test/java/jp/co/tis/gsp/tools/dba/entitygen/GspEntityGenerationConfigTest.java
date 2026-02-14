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
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * GspEntityGenerationConfigのテスト。
 */
public class GspEntityGenerationConfigTest {

    @Test
    public void testCreateConfiguration() {
        File outputDir = new File("target/test-output");

        Configuration config = GspEntityGenerationConfig.create(
            "jdbc:h2:mem:test", "sa", "", "org.h2.Driver",
            "PUBLIC", "jp.co.tis.gsptest", "entity",
            outputDir, "jpa",
            "(SCHEMA_INFO|.*\\$.*)", false
        );

        assertNotNull("設定が生成される", config);
        assertNotNull("JDBC設定がある", config.getJdbc());
        assertEquals("JDBC URL", "jdbc:h2:mem:test", config.getJdbc().getUrl());
        assertEquals("JDBCユーザ", "sa", config.getJdbc().getUser());
        assertEquals("JDBCドライバ", "org.h2.Driver", config.getJdbc().getDriver());

        assertNotNull("Generator設定がある", config.getGenerator());
        assertEquals("Generator名", GspJpaEntityGenerator.class.getName(),
                     config.getGenerator().getName());
        assertEquals("Strategy名", GspGeneratorStrategy.class.getName(),
                     config.getGenerator().getStrategy().getName());

        assertNotNull("Database設定がある", config.getGenerator().getDatabase());
        assertEquals("Database名", "org.jooq.meta.jdbc.JDBCDatabase",
                     config.getGenerator().getDatabase().getName());
        assertEquals("スキーマ", "PUBLIC",
                     config.getGenerator().getDatabase().getInputSchema());

        assertNotNull("Target設定がある", config.getGenerator().getTarget());
        assertEquals("パッケージ名", "jp.co.tis.gsptest.entity",
                     config.getGenerator().getTarget().getPackageName());
    }

    @Test
    public void testConvertIgnorePattern() {
        assertEquals("SCHEMA_INFO|.*\\$.*",
            GspEntityGenerationConfig.convertIgnorePattern("(SCHEMA_INFO|.*\\$.*)"));
        assertEquals("SCHEMA_INFO",
            GspEntityGenerationConfig.convertIgnorePattern("(SCHEMA_INFO)"));
        assertEquals("",
            GspEntityGenerationConfig.convertIgnorePattern(""));
        assertEquals("",
            GspEntityGenerationConfig.convertIgnorePattern(null));
    }

    @Test
    public void testGenerateSettings() {
        File outputDir = new File("target/test-output");

        Configuration config = GspEntityGenerationConfig.create(
            "jdbc:h2:mem:test", "sa", "", "org.h2.Driver",
            "PUBLIC", "jp.co.tis.gsptest", "entity",
            outputDir, "jpa", null, false
        );

        // POJO生成ON、他はOFF
        assertTrue("POJO生成ON", config.getGenerator().getGenerate().isPojos());
    }
}
