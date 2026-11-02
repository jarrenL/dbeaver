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
package org.jkiss.dbeaver.debug.core;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.LaunchConfigurationDelegate;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.debug.DBGConstants;
import org.jkiss.dbeaver.debug.DBGController;
import org.jkiss.dbeaver.debug.DBGControllerFactory;
import org.jkiss.dbeaver.debug.DBGException;
import org.jkiss.dbeaver.debug.DBGLaunchGuard;
import org.jkiss.dbeaver.debug.core.model.DatabaseDebugTarget;
import org.jkiss.dbeaver.debug.core.model.DatabaseProcess;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.utils.GeneralUtils;

import java.util.HashMap;
import java.util.Map;

public class DatabaseLaunchDelegate extends LaunchConfigurationDelegate {

    @Override
    public void launch(
        ILaunchConfiguration configuration,
        String mode,
        ILaunch launch,
        IProgressMonitor monitor
    ) throws CoreException {
        IProgressMonitor launchMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        if (launchMonitor.isCanceled()) {
            return;
        }
        DBPDataSourceContainer datasourceDescriptor = getDataSourceContainer(configuration);
        Map<String, Object> attributes = new HashMap<>(configuration.getAttributes());
        DBGLaunchGuard guard = GeneralUtils.adapt(datasourceDescriptor, DBGLaunchGuard.class);
        if (launchMonitor.isCanceled()) {
            return;
        }
        try {
            if (guard != null && !guard.prepareLaunch(datasourceDescriptor, attributes, new DefaultProgressMonitor(launchMonitor))) {
                return;
            }
        } catch (DBGException e) {
            throw new CoreException(GeneralUtils.makeExceptionStatus(e));
        }
        if (launchMonitor.isCanceled()) {
            return;
        }
        DBGController controller = createController(datasourceDescriptor, attributes);
        if (controller == null) {
            throw new CoreException(DebugUtils.newErrorStatus(
                "Unable to find debug controller for datasource " + datasourceDescriptor.getId()));
        }
        DatabaseProcess process = createProcess(launch, configuration.getName());
        DatabaseDebugTarget target = createDebugTarget(launch, controller, process);
        target.connect(launchMonitor);
        launch.addDebugTarget(target);
    }

    @NotNull
    protected DBPDataSourceContainer getDataSourceContainer(@NotNull ILaunchConfiguration configuration) throws CoreException {
        return DebugUtils.getDataSourceContainer(configuration);
    }

    protected DBGController createController(
        @NotNull DBPDataSourceContainer dataSourceContainer,
        @NotNull Map<String, Object> attributes
    ) throws CoreException {
        try {
            DBGControllerFactory controllerFactory = GeneralUtils.adapt(dataSourceContainer, DBGControllerFactory.class);
            if (controllerFactory != null) {
                return controllerFactory.createController(dataSourceContainer, attributes);
            }
            throw new DBGException(
                NLS.bind("Unable to find controller factory for datasource \"{0}\"", dataSourceContainer.getDriver().getProviderId())
            );
        } catch (DBGException e) {
            throw new CoreException(GeneralUtils.makeExceptionStatus(e));
        }
    }

    @NotNull
    protected DatabaseProcess createProcess(@NotNull ILaunch launch, @NotNull String name) {
        return new DatabaseProcess(launch, name);
    }

    protected DatabaseDebugTarget createDebugTarget(
        @NotNull ILaunch launch,
        @NotNull DBGController controller,
        @NotNull DatabaseProcess process
    ) {
        return new DatabaseDebugTarget(DBGConstants.MODEL_IDENTIFIER_DATABASE, launch, process, controller);
    }

}
