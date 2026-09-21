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
package org.jkiss.dbeaver.ext.gaussdb.model.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.postgresql.model.data.PostgreStringValueHandler;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Text editor and validator for GaussDB FLOATVECTOR and BOOLVECTOR values.
 */
public class GaussDBVectorValueHandler extends PostgreStringValueHandler {
    public static final GaussDBVectorValueHandler FLOAT_VECTOR = new GaussDBVectorValueHandler(false);
    public static final GaussDBVectorValueHandler BOOL_VECTOR = new GaussDBVectorValueHandler(true);

    private final boolean booleanVector;

    private GaussDBVectorValueHandler(boolean booleanVector) {
        this.booleanVector = booleanVector;
    }

    @Nullable
    @Override
    public Object getValueFromObject(
        @NotNull DBCSession session,
        @NotNull DBSTypedObject type,
        @Nullable Object object,
        boolean copy,
        boolean validateValue
    ) throws DBCException {
        Object converted = super.getValueFromObject(session, type, object, copy, validateValue);
        if (converted == null) {
            return null;
        }
        return normalizeVector(converted.toString(), booleanVector);
    }

    @NotNull
    static String normalizeVector(@NotNull String input, boolean booleanVector) throws DBCException {
        String value = input.trim();
        if (value.length() < 2 || value.charAt(0) != '[' || value.charAt(value.length() - 1) != ']') {
            throw new DBCException("GaussDB vector value must use [v1,v2,...] syntax");
        }
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) {
            throw new DBCException("GaussDB vector must contain at least one element");
        }
        List<String> elements = new ArrayList<>();
        for (String item : body.split(",", -1)) {
            String element = item.trim();
            if (element.isEmpty()) {
                throw new DBCException("GaussDB vector contains an empty element");
            }
            if (booleanVector) {
                if (!"0".equals(element) && !"1".equals(element)) {
                    throw new DBCException("BOOLVECTOR elements must be 0 or 1");
                }
            } else {
                try {
                    float number = Float.parseFloat(element);
                    if (!Float.isFinite(number)) {
                        throw new NumberFormatException("non-finite");
                    }
                } catch (NumberFormatException e) {
                    throw new DBCException("FLOATVECTOR contains an invalid number: " + element, e);
                }
            }
            elements.add(element);
        }
        return "[" + String.join(",", elements) + "]";
    }
}
