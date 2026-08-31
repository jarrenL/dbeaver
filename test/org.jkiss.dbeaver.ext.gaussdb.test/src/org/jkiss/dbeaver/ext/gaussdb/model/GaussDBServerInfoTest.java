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
package org.jkiss.dbeaver.ext.gaussdb.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GaussDBServerInfoTest {

    @Test
    public void parsesGaussDB507ProductVersion() {
        Assertions.assertEquals(
            "507.0.0",
            GaussDBServerInfo.parseProductVersion(
                "gaussdb (GaussDB Kernel 507.0.0 build d791c80a) compiled at 2026-05-31"
            )
        );
    }

    @Test
    public void mapsDeploymentNames() {
        Assertions.assertEquals(
            GaussDBServerInfo.Deployment.DISTRIBUTED,
            GaussDBServerInfo.Deployment.fromValue("Distribute")
        );
        Assertions.assertEquals(
            GaussDBServerInfo.Deployment.CENTRALIZED,
            GaussDBServerInfo.Deployment.fromValue("Centralized")
        );
        Assertions.assertEquals(
            GaussDBServerInfo.Deployment.UNKNOWN,
            GaussDBServerInfo.Deployment.fromValue(null)
        );
    }
}
