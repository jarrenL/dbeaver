/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.ext.postgresql.internal.PostgreSQLMessages;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.tasks.nativetool.AbstractNativeToolHandler;
import org.jkiss.dbeaver.tasks.nativetool.AbstractNativeToolSettings;
import org.jkiss.dbeaver.tasks.nativetool.NativeToolUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

public abstract class PostgreNativeToolHandler<SETTINGS extends AbstractNativeToolSettings<BASE_OBJECT>, BASE_OBJECT extends DBSObject, PROCESS_ARG>
    extends AbstractNativeToolHandler<SETTINGS, BASE_OBJECT, PROCESS_ARG> {

    public boolean isUseStreamTransfer(String targetFile) {
        return !IOUtils.isLocalFile(targetFile);
    }

    protected boolean requiresLocalTransferFile(SETTINGS settings, String file) {
        if (!isUseStreamTransfer(file)) {
            return false;
        }
        DBPDataSourceContainer container = settings.getDataSourceContainer();
        return container.getDataSource() instanceof PostgreDataSource dataSource &&
            !dataSource.getServerType().supportsNativeToolStreaming();
    }

    protected static void copyTransferPath(@NotNull Path source, @NotNull Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.createDirectories(target);
        try (var paths = Files.walk(source)) {
            try {
                paths.forEach(path -> {
                    try {
                        Path relative = source.relativize(path);
                        Path destination = target.resolve(relative.toString());
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }

    protected static void deleteLocalTransferPath(@Nullable Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException e) {
                    // Temporary transfer files are also cleaned by the operating system.
                }
            });
        } catch (IOException e) {
            // Temporary transfer files are also cleaned by the operating system.
        }
    }

    @Override
    protected void setupProcessParameters(DBRProgressMonitor monitor, SETTINGS settings, PROCESS_ARG arg, ProcessBuilder process) {
        if (this instanceof PostgreDatabaseRestoreHandler || this instanceof PostgreDatabaseBackupAllHandler) {
            // These tasks do not use stdout as a payload. Their log reader consumes
            // stderr only; leaving stdout piped can deadlock a verbose native tool.
            process.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        if (usesPasswordPipe(settings)) {
            process.environment().remove("PGPASSWORD");
        } else {
            String userPassword = nativePassword(monitor, settings);
            if (!CommonUtils.isEmpty(userPassword)) {
                process.environment().put("PGPASSWORD", userPassword);
            }
        }
        DBPDataSourceContainer container = settings.getDataSourceContainer();
        if (container.getDataSource() instanceof PostgreDataSource dataSource && settings.getClientHome() != null) {
            dataSource.getServerType().configureNativeToolEnvironment(settings.getClientHome(), process.environment());
        }
    }

    private boolean usesPasswordPipe(SETTINGS settings) {
        return settings.getDataSourceContainer().getDataSource() instanceof PostgreDataSource source
            && source.getServerType() != null && source.getServerType().usesNativePasswordPipe();
    }

    private String nativePassword(DBRProgressMonitor monitor, SETTINGS settings) {
        String password = settings.getToolUserPassword();
        return CommonUtils.isEmpty(password) ? getDataSourcePassword(monitor, settings) : password;
    }

    /** Authentication bytes must never be placed in argv or logs. */
    protected void writeNativePassword(DBRProgressMonitor monitor, SETTINGS settings, Process process) throws IOException {
        if (!usesPasswordPipe(settings)) {
            return;
        }
        String password = CommonUtils.notEmpty(nativePassword(monitor, settings));
        if (password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0 || password.indexOf('\0') >= 0) {
            process.destroy();
            throw new IOException(PostgreSQLMessages.native_password_pipe_invalid);
        }
        try {
            var input = process.getOutputStream();
            input.write((password + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
            // Script execution appends its SQL after the authentication line.
            if (!(this instanceof PostgreScriptExecuteHandler)) {
                input.close();
            }
        } catch (IOException e) {
            process.destroy();
            throw new IOException(PostgreSQLMessages.native_password_pipe_write_error, e);
        }
    }

    @Override
    protected void startProcessHandler(DBRProgressMonitor monitor, DBTTask task, SETTINGS settings, PROCESS_ARG arg,
                                       ProcessBuilder builder, Process process, Log log) throws IOException, DBException {
        writeNativePassword(monitor, settings, process);
        super.startProcessHandler(monitor, task, settings, arg, builder, process, log);
    }

    @Override
    public void fillProcessParameters(SETTINGS settings, PROCESS_ARG processArg, List<String> cmd) throws IOException {
        boolean isRestoreByPsql = this instanceof PostgreDatabaseRestoreHandler
            && settings instanceof PostgreBackupRestoreSettings postgreBackupRestoreSettings
            && postgreBackupRestoreSettings.getFormat() == PostgreBackupRestoreSettings.ExportFormat.PLAIN;
        DBPDataSourceContainer dataSourceContainer = settings.getDataSourceContainer();
        String toolName = this instanceof PostgreDatabaseBackupHandler ? "pg_dump" :
                isRestoreByPsql ? "psql" :
                    this instanceof PostgreDatabaseRestoreHandler ? "pg_restore" :
                        this instanceof PostgreDatabaseBackupAllHandler ? "pg_dumpall" :
                            "psql";
        if (dataSourceContainer.getDataSource() instanceof PostgreDataSource dataSource) {
            toolName = dataSource.getServerType().getNativeToolName(toolName);
        }
        File dumpBinary = RuntimeUtils.getNativeClientBinary(
            settings.getClientHome(), PostgreConstants.BIN_FOLDER,
            toolName
        ); //$NON-NLS-1$
        String dumpPath = dumpBinary.getAbsolutePath();
        cmd.add(dumpPath);

        if (isVerbose() && !isRestoreByPsql) {
            cmd.add("--verbose");
        }
        NativeToolUtils.addHostAndPortParamsToCmd(dataSourceContainer, cmd);
        String toolUserName = settings.getToolUserName();
        if (CommonUtils.isEmpty(toolUserName)) {
            toolUserName = dataSourceContainer.getActualConnectionConfiguration().getUserName();
        }
        cmd.add("--username=" + toolUserName);

        settings.addExtraCommandArgs(cmd);
        if (usesPasswordPipe(settings)) {
            cmd.add("--pipeline");
        }
    }

    public boolean isVerbose() {
        return false;
    }

    protected abstract boolean isExportWizard();

    public static String escapeCLIIdentifier(String name) {
        if (RuntimeUtils.isWindows()) {
            // On Windows it is simple
            return "\"" + name.replace("\"", "\\\"") + "\"";
        } else {
            // On Unixes it is more tricky (https://unix.stackexchange.com/questions/30903/how-to-escape-quotes-in-shell)
            //return "\"" + name.replace("\"", "\"\\\"\"") + "\"";
            return name;
            //return "\"" + name.replace("\"", "\\\"") + "\"";
        }
    }

}
