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
package org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal;

import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.debug.DBGLaunchGuard;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugConstants;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugCore;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugSessionSettings;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.Map;

/** Repeating a launch must ask again rather than failing on a consumed acknowledgement. */
public class GaussDBDebugLaunchGuard implements DBGLaunchGuard {
    @Override
    public boolean prepareLaunch(
        @NotNull DBPDataSourceContainer container,
        @NotNull Map<String, Object> attributes,
        @NotNull DBRProgressMonitor monitor
    ) throws DBGException {
        if (monitor.isCanceled()) {
            return false;
        }
        try {
            var routine = GaussDBDebugCore.resolveRoutine(monitor, container, attributes);
            String error = GaussDBDebugCore.getRoutineEligibilityError(monitor, routine);
            if (error != null) {
                throw new DBGException(error);
            }
            if (!GaussDBDebugSessionSettings.requiresConsent(routine)) {
                return true;
            }
            if (GaussDBDebugSessionSettings.claimConsentToken(attributes)) {
                return true;
            }
            String setting = NLS.bind(GaussDBDebugMessages.session_settings_description, routine.getInvocationSchema());
            String question = NLS.bind(GaussDBDebugMessages.session_settings_launch_confirmation, new Object[]{
                container.getName(), routine.getDatabase().getName(), routine.getInvocationName(monitor), setting
            });
            if (monitor.isCanceled() || !UIUtils.confirmAction(GaussDBDebugMessages.session_settings_group, question)
                || monitor.isCanceled()) {
                return false;
            }
            attributes.put(GaussDBDebugConstants.ATTR_SESSION_SETTINGS_CONSENT_TOKEN,
                GaussDBDebugSessionSettings.issueConsentToken());
            return true;
        } catch (DBException e) {
            if (monitor.isCanceled()) {
                return false;
            }
            if (e instanceof DBGException debugException) {
                throw debugException;
            }
            throw new DBGException("Unable to prepare GaussDB debug launch", e);
        }
    }
}
