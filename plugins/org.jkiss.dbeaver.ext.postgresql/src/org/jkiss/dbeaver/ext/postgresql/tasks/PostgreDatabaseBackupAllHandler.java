/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package org.jkiss.dbeaver.ext.postgresql.tasks;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreServerExtension;
import org.jkiss.dbeaver.model.fs.DBFUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.registry.task.TaskPreferenceStore;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PostgreDatabaseBackupAllHandler
    extends PostgreNativeToolHandler<PostgreBackupAllSettings, DBSObject, PostgreDatabaseBackupAllInfo> {
    private final Map<PostgreDatabaseBackupAllInfo, Path> localTransferFiles = new ConcurrentHashMap<>();

    @Override
    protected boolean isExportWizard() {
        return true;
    }

    @Override
    public Collection<PostgreDatabaseBackupAllInfo> getRunInfo(PostgreBackupAllSettings settings) {
        return settings.getExportObjects();
    }

    @Override
    protected PostgreBackupAllSettings createTaskSettings(DBRRunnableContext context, DBTTask task) throws DBException {
        PostgreBackupAllSettings settings = new PostgreBackupAllSettings(task.getProject());
        settings.loadSettings(context, new TaskPreferenceStore(task));
        return settings;
    }

    @Override
    protected boolean validateTaskParameters(DBTTask task, PostgreBackupAllSettings settings, Log log) {
        if (PostgreSQLTasks.TASK_DATABASE_BACKUP_ALL.equals(task.getType().getId())) {
            for (PostgreDatabaseBackupAllInfo exportObject : settings.getExportObjects()) {
                String dir = settings.getOutputFolder(exportObject);
                try {
                    Path outputFolderPath = DBFUtils.resolvePathFromString(new VoidProgressMonitor(), task.getProject(), dir);
                    if (!Files.exists(outputFolderPath)) {
                            Files.createDirectories(outputFolderPath);
                    }
                } catch (Exception e) {
                    log.error("Can't create directory '" + dir + "'", e);
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected boolean needsModelRefresh() {
        return false;
    }

    @Override
    public boolean isVerbose() {
        return true;
    }

    @Override
    protected boolean isLogInputStream() {
        return false;
    }

    @Override
    public void fillProcessParameters(
        PostgreBackupAllSettings settings,
        PostgreDatabaseBackupAllInfo arg,
        List<String> cmd
    ) throws IOException {

        super.fillProcessParameters(settings, arg, cmd);
        PostgreServerExtension serverType = arg.getDataSource().getServerType();

        if (!CommonUtils.isEmpty(settings.getEncoding())) {
            cmd.add("--encoding=" + settings.getEncoding());
        }
        if (settings.isExportOnlyMetadata()) {
            cmd.add("--schema-only");
        }
        if (settings.isExportOnlyGlobals()) {
            cmd.add("--globals-only");
        }
        if (settings.isExportOnlyRoles()) {
            cmd.add("--roles-only");
        }
        if (settings.isExportOnlyTablespaces()) {
            cmd.add("--tablespaces-only");
        }
        if (settings.isNoPrivileges()) {
            cmd.add("--no-privileges");
        }
        if (settings.isNoOwner()) {
            cmd.add("--no-owner");
        }
        if (!settings.isAddRolesPasswords()) {
            // We do not want to dump users passwords by default
            if (serverType.supportsNativeBackupAllPasswordSuppression()) {
                cmd.add("--no-role-passwords");
            }
        }

        cmd.add("--file");
        if (requiresLocalTransferFile(settings, settings.getOutputFile(arg))) {
            Path localFile = Files.createTempFile("dbeaver-gaussdb-backup-all-", ".sql");
            localTransferFiles.put(arg, localFile);
            cmd.add(localFile.toString());
        } else {
            cmd.add(settings.getOutputFile(arg));
        }

        // Databases
        if (settings.getExportObjects().isEmpty()) {
            // If not specified, the postgres database will be used, and if that does not exist, template1 will be used.
        } else {
            List<PostgreDatabase> includedDatabases = arg.getDatabases();
            if (!CommonUtils.isEmpty(includedDatabases)) {
                final PostgreDataSource dataSource = arg.getDataSource();
                final List<PostgreDatabase> allDatabases = dataSource.getDatabases();
                if (allDatabases.size() != includedDatabases.size()) {
                    if (!serverType.supportsNativeBackupAllDatabaseFilter()) {
                        throw new IOException(serverType.getServerTypeName() +
                            " cluster backup can export either all databases or globals only; " +
                            "the native tool has no database filter option");
                    }
                    // pg_dumpall does not have parameter "include database", only "exclude database".
                    // So we need to create list of excluded databases
                    List<PostgreDatabase> allDatabasesCopy = new ArrayList<>(allDatabases);
                    allDatabasesCopy.removeAll(includedDatabases);
                    for (PostgreDatabase database : allDatabasesCopy) {
                        // Use explicit quotes in case of quoted identifiers (#5950)
                        cmd.add("--exclude-database=" + escapeCLIIdentifier(database.getName()));
                    }
                }
            }
        }
    }

    @Override
    public boolean executeProcess(
        DBRProgressMonitor monitor,
        DBTTask task,
        PostgreBackupAllSettings settings,
        PostgreDatabaseBackupAllInfo arg,
        Log taskLog
    ) throws IOException, InterruptedException {
        try {
            boolean result = super.executeProcess(monitor, task, settings, arg, taskLog);
            PostgreServerExtension serverType = arg.getDataSource().getServerType();
            Path localFile = localTransferFiles.get(arg);
            Path output = localFile == null
                ? resolveOutputPath(monitor, task, settings, arg)
                : localFile;
            if (!settings.isAddRolesPasswords() && !serverType.supportsNativeBackupAllPasswordSuppression()) {
                suppressRolePasswords(output);
            }
            if (localFile != null) {
                Path target = resolveOutputPath(monitor, task, settings, arg);
                copyTransferPath(localFile, target);
            }
            return result;
        } finally {
            deleteLocalTransferPath(localTransferFiles.remove(arg));
        }
    }

    @NotNull
    private static Path resolveOutputPath(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBTTask task,
        @NotNull PostgreBackupAllSettings settings,
        @NotNull PostgreDatabaseBackupAllInfo arg
    ) throws IOException {
        try {
            return DBFUtils.resolvePathFromString(monitor, task.getProject(), settings.getOutputFile(arg));
        } catch (DBException e) {
            throw new IOException("Cannot resolve backup output path", e);
        }
    }

    protected static void suppressRolePasswords(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Cannot resolve backup output folder for " + output);
        }
        Path sanitized = Files.createTempFile(parent, ".dbeaver-gaussdb-sanitize-", ".sql");
        try {
            try (var reader = Files.newBufferedReader(output, StandardCharsets.ISO_8859_1);
                 var writer = Files.newBufferedWriter(sanitized, StandardCharsets.ISO_8859_1)) {
                PostgreRolePasswordSanitizer.sanitize(reader, writer);
            }
            try {
                Files.move(sanitized, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(sanitized, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(sanitized);
        }
    }

    @Override
    protected List<String> getCommandLine(PostgreBackupAllSettings settings, PostgreDatabaseBackupAllInfo arg) throws IOException {
        List<String> cmd = new ArrayList<>();
        fillProcessParameters(settings, arg, cmd);
        return cmd;
    }
}
