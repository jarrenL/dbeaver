/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal;

import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.debug.ui.DBGConfigurationPanel;
import org.jkiss.dbeaver.debug.ui.DBGConfigurationPanelContainer;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugConstants;
import org.jkiss.dbeaver.ext.gaussdb.debug.core.GaussDBDebugCore;
import org.jkiss.dbeaver.ext.gaussdb.model.GaussDBProcedure;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreProcedureParameter;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.navigator.*;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameter;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.CSmartSelector;
import org.jkiss.dbeaver.ui.controls.CustomTableEditor;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GaussDBDebugPanelRoutine implements DBGConfigurationPanel {
    private DBGConfigurationPanelContainer container;
    private CSmartSelector<GaussDBProcedure> routineSelector;
    private Table parametersTable;
    private GaussDBProcedure selectedRoutine;
    private final Map<DBSProcedureParameter, String> parameterValues = new HashMap<>();

    @Override
    public void createPanel(@NotNull Composite parent, DBGConfigurationPanelContainer container) {
        this.container = container;
        Composite routineGroup = UIUtils.createTitledComposite(parent, "GaussDB routine", 2, GridData.FILL_HORIZONTAL);
        UIUtils.createControlLabel(routineGroup, "Routine");
        routineSelector = new CSmartSelector<>(routineGroup, SWT.BORDER | SWT.DROP_DOWN | SWT.READ_ONLY, new LabelProvider() {
            @Override
            public Image getImage(Object element) {
                return DBeaverIcons.getImage(DBIcon.TREE_PROCEDURE);
            }

            @Override
            public String getText(Object element) {
                return element instanceof GaussDBProcedure routine ? routine.getFullQualifiedSignature() : "N/A";
            }
        }) {
            @Override
            protected void dropDown(boolean drop) {
                if (drop) {
                    selectRoutine(parent.getShell());
                }
            }
        };
        routineSelector.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Composite parametersGroup = UIUtils.createTitledComposite(parent, "Input parameters", 1, GridData.FILL_BOTH);
        parametersTable = new Table(parametersGroup, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL);
        parametersTable.setLayoutData(new GridData(GridData.FILL_BOTH));
        parametersTable.setHeaderVisible(true);
        parametersTable.setLinesVisible(true);
        UIUtils.createTableColumn(parametersTable, SWT.LEFT, "Name").setWidth(140);
        UIUtils.createTableColumn(parametersTable, SWT.LEFT, "Value").setWidth(240);
        UIUtils.createTableColumn(parametersTable, SWT.LEFT, "Type").setWidth(140);
        new CustomTableEditor(parametersTable) {
            {
                firstTraverseIndex = 1;
                lastTraverseIndex = 1;
                editOnEnter = false;
            }

            @Override
            protected Control createEditor(Table table, int index, TableItem item) {
                if (index != 1) {
                    return null;
                }
                Text editor = new Text(table, SWT.BORDER);
                editor.setText(item.getText(1));
                editor.selectAll();
                return editor;
            }

            @Override
            protected void saveEditorValue(Control control, int index, TableItem item) {
                String value = ((Text) control).getText();
                item.setText(1, value);
                parameterValues.put((DBSProcedureParameter) item.getData(), value);
                container.updateDialogState();
            }
        };
    }

    private void selectRoutine(Shell shell) {
        DBNModel navigator = DBWorkbench.getPlatform().getNavigatorModel();
        DBNDatabaseNode dataSourceNode = navigator.getNodeByObject(container.getDataSource());
        if (dataSourceNode == null) {
            return;
        }
        DBNNode selectedNode = DBWorkbench.getPlatformUI().selectObject(
            shell, "Select GaussDB routine to debug", dataSourceNode,
            selectedRoutine == null ? null : navigator.getNodeByObject(selectedRoutine),
            new Class[]{DBSInstance.class, DBSObjectContainer.class, GaussDBProcedure.class},
            new Class[]{GaussDBProcedure.class}, null);
        if (selectedNode instanceof DBNDatabaseNode databaseNode && databaseNode.getObject() instanceof GaussDBProcedure routine) {
            try {
                String eligibilityError = GaussDBDebugCore.getRoutineEligibilityError(new VoidProgressMonitor(), routine);
                if (eligibilityError != null) {
                    selectedRoutine = null;
                    routineSelector.removeAll();
                    parametersTable.removeAll();
                    container.setWarningMessage(eligibilityError);
                    container.updateDialogState();
                    return;
                }
            } catch (DBException e) {
                selectedRoutine = null;
                container.setWarningMessage(e.getMessage());
                container.updateDialogState();
                return;
            }
            selectedRoutine = routine;
            routineSelector.removeAll();
            routineSelector.addItem(routine);
            routineSelector.select(routine);
            updateParameters();
            container.setWarningMessage(null);
            container.updateDialogState();
        }
    }

    @Override
    public void loadConfiguration(DBPDataSourceContainer dataSource, Map<String, Object> configuration) {
        if (CommonUtils.toLong(configuration.get(GaussDBDebugConstants.ATTR_ROUTINE_OID)) != 0 && dataSource != null) {
            try {
                container.getRunnableContext().run(true, true, monitor -> {
                    try {
                        selectedRoutine = GaussDBDebugCore.resolveRoutine(monitor, dataSource, configuration);
                        String eligibilityError = GaussDBDebugCore.getRoutineEligibilityError(monitor, selectedRoutine);
                        if (eligibilityError != null) {
                            selectedRoutine = null;
                            throw new DBException(eligibilityError);
                        }
                    } catch (DBException e) {
                        throw new InvocationTargetException(e);
                    }
                });
                container.setWarningMessage(null);
            } catch (InvocationTargetException e) {
                container.setWarningMessage(e.getTargetException().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (selectedRoutine == null) {
            return;
        }
        Object rawValues = configuration.get(GaussDBDebugConstants.ATTR_ROUTINE_PARAMETERS);
        if (rawValues instanceof List<?> values && values.size() == selectedRoutine.getInputParameters().size()) {
            for (int i = 0; i < values.size(); i++) {
                parameterValues.put(selectedRoutine.getInputParameters().get(i), CommonUtils.toString(values.get(i)));
            }
        }
        routineSelector.addItem(selectedRoutine);
        routineSelector.select(selectedRoutine);
        updateParameters();
    }

    private void updateParameters() {
        parametersTable.removeAll();
        for (PostgreProcedureParameter parameter : selectedRoutine.getInputParameters()) {
            TableItem item = new TableItem(parametersTable, SWT.NONE);
            item.setData(parameter);
            item.setImage(DBeaverIcons.getImage(DBIcon.TREE_ATTRIBUTE));
            item.setText(0, parameter.getName());
            item.setText(1, CommonUtils.toString(parameterValues.get(parameter)));
            item.setText(2, parameter.getFullTypeName());
        }
    }

    @Override
    public void saveConfiguration(DBPDataSourceContainer dataSource, Map<String, Object> configuration) {
        if (selectedRoutine == null) {
            configuration.remove(GaussDBDebugConstants.ATTR_ROUTINE_OID);
            return;
        }
        GaussDBDebugCore.saveRoutine(selectedRoutine, configuration);
        List<String> values = new ArrayList<>();
        for (PostgreProcedureParameter parameter : selectedRoutine.getInputParameters()) {
            values.add(parameterValues.get(parameter));
        }
        configuration.put(GaussDBDebugConstants.ATTR_ROUTINE_PARAMETERS, values);
    }

    @Override
    public boolean isValid() {
        return selectedRoutine != null && selectedRoutine.getInputParameters().stream()
            .allMatch(parameterValues::containsKey);
    }
}
