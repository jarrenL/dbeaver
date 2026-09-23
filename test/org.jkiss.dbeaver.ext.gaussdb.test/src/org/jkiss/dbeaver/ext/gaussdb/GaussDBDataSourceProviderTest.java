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
package org.jkiss.dbeaver.ext.gaussdb;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GaussDBDataSourceProviderTest {

    @Test
    public void emptyHostEntriesAreIgnoredWithoutInventingAnEndpoint() {
        Assertions.assertEquals("", GaussDBDataSourceProvider.formatHosts(null, "8000"));
        Assertions.assertEquals("", GaussDBDataSourceProvider.formatHosts(" , , ", "8000"));
        Assertions.assertEquals("cn1:8000,cn2:8000",
            GaussDBDataSourceProvider.formatHosts(" ,cn1, ,cn2, ", "8000"));
    }

    @Test
    public void missingDefaultPortDoesNotAppendLiteralNullOrColon() {
        Assertions.assertEquals("cn1,cn2:8002", GaussDBDataSourceProvider.formatHosts("cn1,cn2:8002", null));
        Assertions.assertEquals("[::1]", GaussDBDataSourceProvider.formatHosts("::1", ""));
    }

    @Test
    public void bracketedIpv6GetsExactlyOneDefaultPort() {
        Assertions.assertEquals("[::1]:8000,[2001:db8::1]:8001",
            GaussDBDataSourceProvider.formatHosts("[::1],[2001:db8::1]:8001", "8000"));
    }

    @Test
    public void manualHostsCannotInjectQueryFragmentOrUserInfo() {
        for (String host : java.util.List.of("cn1?ssl=false", "cn1#fragment", "user@cn1", "cn1/database")) {
            Assertions.assertThrows(IllegalArgumentException.class,
                () -> GaussDBDataSourceProvider.formatHosts(host, "8000"), host);
        }
    }

    @Test
    public void formatsMultiHostAddressWithSharedPort() {
        Assertions.assertEquals(
            "cn1.example:8000,cn2.example:8000",
            GaussDBDataSourceProvider.formatHosts("cn1.example, cn2.example", "8000")
        );
    }

    @Test
    public void preservesPerHostPortsAndFormatsIpv6() {
        Assertions.assertEquals(
            "cn1.example:8001,[2001:db8::1]:8000,[2001:db8::2]:8002",
            GaussDBDataSourceProvider.formatHosts(
                "cn1.example:8001,2001:db8::1,[2001:db8::2]:8002",
                "8000"
            )
        );
    }

    @Test
    public void rejectsUrlComponentsInManualHostList() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> GaussDBDataSourceProvider.formatHosts("user@cn1.example/database", "8000")
        );
    }
}
