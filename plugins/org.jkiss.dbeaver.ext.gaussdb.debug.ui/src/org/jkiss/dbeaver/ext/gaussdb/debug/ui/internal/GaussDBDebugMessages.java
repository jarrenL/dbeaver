/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jkiss.dbeaver.ext.gaussdb.debug.ui.internal;

import org.eclipse.osgi.util.NLS;

public final class GaussDBDebugMessages extends NLS {
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
