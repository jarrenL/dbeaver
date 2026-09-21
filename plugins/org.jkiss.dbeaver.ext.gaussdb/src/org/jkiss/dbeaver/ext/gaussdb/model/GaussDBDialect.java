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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.gaussdb.GaussDBConstants;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDialect;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.sql.parser.tokens.SQLDelimiterToken;
import org.jkiss.dbeaver.model.text.parser.TPRule;
import org.jkiss.dbeaver.model.text.parser.TPTokenAbstract;
import org.jkiss.dbeaver.model.sql.SQLSyntaxManager;
import org.jkiss.dbeaver.model.sql.parser.SQLParserActionKind;
import org.jkiss.dbeaver.model.sql.parser.SQLRuleManager;
import org.jkiss.dbeaver.model.sql.parser.SQLTokenPredicateSet;
import org.jkiss.dbeaver.model.sql.parser.tokens.predicates.TokenPredicateFactory;
import org.jkiss.dbeaver.model.sql.parser.tokens.predicates.TokenPredicateSet;
import org.jkiss.dbeaver.model.sql.parser.tokens.predicates.TokenPredicatesCondition;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * GaussDB SQL dialect.
 * Extends PostgreSQL dialect with GaussDB-specific keywords, types, and syntax.
 */
public class GaussDBDialect extends PostgreDialect {
    private GaussDBDataSource dataSource;
    private SQLTokenPredicateSet plsqlPredicates;

    void setDataSource(GaussDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    private boolean supportsPLSQLBlocks() {
        return dataSource == null || !dataSource.getAvailableInstances().isEmpty()
            && dataSource.getDefaultInstance() instanceof GaussDBDatabase database
            && database.getCompatibility() == DBCompatibilityEnum.ORACLE;
    }

    @Override
    public String[][] getBlockBoundStrings() {
        // Unlike PostgreSQL, GaussDB also accepts unquoted PL/SQL bodies.
        return supportsPLSQLBlocks()
            ? new String[][]{{"BEGIN", "END"}, {"LOOP", "END LOOP"}, {"CASE", "END CASE"}}
            : super.getBlockBoundStrings();
    }

    @Override
    public String[] getBlockHeaderStrings() {
        return supportsPLSQLBlocks() ? new String[]{"DECLARE"} : super.getBlockHeaderStrings();
    }

    @NotNull
    @Override
    public synchronized SQLTokenPredicateSet getSkipTokenPredicates() {
        if (!supportsPLSQLBlocks()) {
            return super.getSkipTokenPredicates();
        }
        if (plsqlPredicates == null) {
            SQLSyntaxManager syntax = new SQLSyntaxManager();
            syntax.init(this, DBWorkbench.getPlatform().getPreferenceStore());
            SQLRuleManager rules = new SQLRuleManager(syntax);
            rules.loadRules();
            TokenPredicateFactory tt = TokenPredicateFactory.makeDialectSpecificFactory(rules);
            plsqlPredicates = TokenPredicateSet.of(
                new TokenPredicatesCondition(SQLParserActionKind.BLOCK_HEADER,
                    tt.sequence("CREATE", tt.optional("OR", "REPLACE"), "PACKAGE"), tt.sequence()),
                new TokenPredicatesCondition(SQLParserActionKind.BEGIN_COMPOUND_BLOCK,
                    tt.sequence("CREATE", tt.optional("OR", "REPLACE"), "PACKAGE", "BODY"), tt.sequence()),
                new TokenPredicatesCondition(SQLParserActionKind.NESTED_BLOCK_HEADER,
                    tt.sequence("CREATE", tt.optional("OR", "REPLACE"), "PACKAGE", "BODY"),
                    tt.alternative("FUNCTION", "PROCEDURE")),
                new TokenPredicatesCondition(SQLParserActionKind.BEGIN_BLOCK,
                    tt.sequence("CREATE", tt.optional("OR", "REPLACE"), "PACKAGE", "BODY"),
                    tt.sequence(tt.not("END"), "IF", tt.not("EXISTS")))
            );
        }
        return plsqlPredicates;
    }

    @NotNull
    @Override
    public TPRule[] extendRules(@Nullable DBPDataSourceContainer container, @NotNull RulePosition position) {
        TPRule[] inherited = super.extendRules(container, position);
        if (!supportsPLSQLBlocks() || position != RulePosition.INITIAL) {
            return inherited;
        }
        TPRule[] rules = Arrays.copyOf(inherited, inherited.length + 1);
        rules[inherited.length] = scanner -> {
            int first = scanner.read();
            scanner.unread();
            if (first != '/') {
                return TPTokenAbstract.UNDEFINED;
            }
            // A slash is a client delimiter only when it occupies its own line.
            int offset = scanner.getOffset();
            int backed = 0;
            boolean lineStart = true;
            while (backed < offset) {
                scanner.unread();
                backed++;
                int previous = scanner.read();
                scanner.unread();
                if (previous == '\n' || previous == '\r') {
                    break;
                }
                if (previous != ' ' && previous != '\t') {
                    lineStart = false;
                    break;
                }
            }
            for (int i = 0; i < backed; i++) {
                scanner.read();
            }
            if (!lineStart) {
                return TPTokenAbstract.UNDEFINED;
            }
            int read = 1;
            scanner.read(); // slash
            int ch;
            do {
                ch = scanner.read();
                read++;
            } while (ch == ' ' || ch == '\t');
            if (ch == '\r' || ch == '\n' || ch == -1) {
                scanner.unread();
                return new SQLDelimiterToken();
            }
            for (int i = 0; i < read; i++) {
                scanner.unread();
            }
            return TPTokenAbstract.UNDEFINED;
        };
        return rules;
    }

    @Override
    public String[] getInnerBlockPrefixes() {
        return new String[]{"AS", "IS"};
    }

    @Override
    public boolean isDelimiterAfterQuery() {
        // Keep the terminator after END <name>, too. It is part of PL/SQL,
        // and the GaussDB JDBC driver accepts it for ordinary SQL as well.
        return supportsPLSQLBlocks();
    }

    @Override
    public boolean isDelimiterAfterBlock() {
        return true;
    }

    public void addExtraDataTypes(String... dataTypes) {
        addDataTypes(Arrays.asList(dataTypes));
    }

    @NotNull
    @Override
    public Collection<String> getDataTypes(@Nullable DBPDataSource dataSource) {
        Collection<String> dataTypes = new LinkedHashSet<>(super.getDataTypes(dataSource));
        dataTypes.addAll(Arrays.asList(GaussDBConstants.GAUSSDB_DATA_TYPES));
        return dataTypes;
    }
}
