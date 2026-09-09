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

package org.jkiss.dbeaver.ext.gaussdb.ui.internal;

import org.eclipse.osgi.util.NLS;

public class GaussDBMessages extends NLS {
    private static final String BUNDLE_NAME = "org.jkiss.dbeaver.ext.gaussdb.ui.internal.GaussDBResources";
    public static String dialog_struct_create_procedure_combo_type;
    public static String dialog_struct_create_procedure_label_name;
    public static String dialog_struct_create_procedure_title;
    public static String dialog_struct_create_function_title;
    public static String dialog_struct_create_procedure_container;
    public static String dialog_struct_create_function_language;
    public static String dialog_struct_create_function_return_type;
    public static String dialog_struct_create_function_language_required;
    public static String dialog_struct_create_function_return_type_required;

    public static String dialog_create_database_deployment_type;
    public static String dialog_create_database_deployment_centralized;
    public static String dialog_create_database_deployment_distributed;
    public static String dialog_create_database_compatibility_mode;

    public static String tree_procedures_node_name;
    public static String tree_functions_node_name;
    public static String package_compile_results;
    public static String package_compile_object;
    public static String package_compile_part;
    public static String package_compile_line;
    public static String package_compile_message;
    public static String package_compile_open_source;
    public static String package_compile_load_failed;

    static {
        // initialize resource bundle
        NLS.initializeMessages(BUNDLE_NAME, GaussDBMessages.class);
    }

    private GaussDBMessages() {
    }
}
