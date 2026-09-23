/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package org.jkiss.dbeaver.ext.gaussdb.debug.core;

import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ResultSetErrorMessagesTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "controls_resultset_error_title | Data error | 数据错误",
        "controls_resultset_error_synchronizing | Error synchronizing data with database | 将数据保存到数据库时出错",
        "controls_resultset_error_generating_script | Error generating script | 生成脚本时出错"
    })
    void packagedErrorResourcesHaveEnglishFallbackAndChineseTranslation(String key, String english, String chinese) throws Exception {
        var bundle = Platform.getBundle("org.jkiss.dbeaver.ui.editors.data");
        assertNotNull(bundle);
        String base = "org/jkiss/dbeaver/ui/controls/resultset/internal/ResultSetMessages";
        for (String suffix : new String[] {"", "_zh"}) {
            var entry = bundle.getEntry(base + suffix + ".properties");
            assertNotNull(entry);
            Properties values = new Properties();
            try (var reader = new InputStreamReader(entry.openStream(), StandardCharsets.UTF_8)) {
                values.load(reader);
            }
            assertEquals(suffix.isEmpty() ? english : chinese, values.getProperty(key));
        }
        var messages = bundle.loadClass(base.replace('/', '.'));
        String resolved = (String) messages.getField(key).get(null);
        assertNotNull(resolved);
        assertFalse(resolved.startsWith("!"), "NLS must resolve the declared field");
    }
}
