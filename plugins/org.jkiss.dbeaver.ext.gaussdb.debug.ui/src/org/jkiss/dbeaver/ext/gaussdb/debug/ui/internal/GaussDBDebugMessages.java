/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal;

import org.eclipse.osgi.util.NLS;

public final class GaussDBDebugMessages extends NLS {
    public static String routine_group;
    public static String routine_label;
    public static String routine_select;
    public static String input_parameters;
    public static String parameter_name;
    public static String parameter_value_column;
    public static String parameter_type;
    public static String parameter_unknown_mode;
    public static String parameter_mode;
    public static String parameter_value;
    public static String parameter_null;
    public static String parameter_default;
    public static String parameter_hint;

    static {
        initializeMessages(GaussDBDebugMessages.class.getName(), GaussDBDebugMessages.class);
    }

    private GaussDBDebugMessages() {
    }
}
