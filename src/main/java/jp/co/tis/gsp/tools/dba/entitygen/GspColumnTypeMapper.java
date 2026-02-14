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

import org.jooq.meta.jaxb.ForcedType;

import java.util.ArrayList;
import java.util.List;

/**
 * DB型→Java型マッピングの設定を生成する。
 * S2JDBC-GenのExtendedXxxGenDialect columnTypeMapをjOOQ ForcedTypeに変換する。
 */
public class GspColumnTypeMapper {

    private GspColumnTypeMapper() {
    }

    /**
     * JSR310対応の型マッピングを生成する。
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
