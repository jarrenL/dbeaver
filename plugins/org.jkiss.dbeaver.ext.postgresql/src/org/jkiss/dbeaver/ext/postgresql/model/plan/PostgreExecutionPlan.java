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
package org.jkiss.dbeaver.ext.postgresql.model.plan;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.exec.plan.DBCPlanCostNode;
import org.jkiss.dbeaver.model.exec.plan.DBCPlanNode;
import org.jkiss.dbeaver.model.exec.plan.DBCPlanSourceFormat;
import org.jkiss.dbeaver.model.exec.plan.DBCQueryPlannerConfiguration;
import org.jkiss.dbeaver.model.impl.plan.AbstractExecutionPlan;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.XMLException;
import org.jkiss.utils.xml.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Postgre execution plan analyser
 */
public class PostgreExecutionPlan extends AbstractExecutionPlan {

    private static final Log log = Log.getLog(PostgreExecutionPlan.class);

    private static final String NODE_PREFIX = "->  ";
    private static final String PROP_PREFIX = "  ";

    private boolean oldQuery;
    private boolean verbose;
    private final String query;
    private final DBCQueryPlannerConfiguration configuration;
    private String planText;
    private List<DBCPlanNode> rootNodes = new ArrayList<>();

    public PostgreExecutionPlan(boolean oldQuery, boolean verbose, String query, DBCQueryPlannerConfiguration configuration)
    {
        this.oldQuery = oldQuery;
        this.verbose = verbose;
        this.query = query;
        this.configuration = configuration;
    }

    public PostgreExecutionPlan(String query, List<PostgrePlanNodeExternal> nodes) {
        this.query = query;
        this.rootNodes = new ArrayList<>();
        this.rootNodes.addAll(nodes);
        this.configuration = new DBCQueryPlannerConfiguration();
    }

    @Nullable
    @Override
    public Object getPlanFeature(@NotNull String feature) {
        if (DBCPlanCostNode.FEATURE_PLAN_COST.equals(feature) ||
            DBCPlanCostNode.FEATURE_PLAN_DURATION.equals(feature) ||
            DBCPlanCostNode.FEATURE_PLAN_ROWS.equals(feature))
        {
            return true;
        }
        return super.getPlanFeature(feature);
    }

    @NotNull
    @Override
    public String getQueryString()
    {
        return query;
    }

    @NotNull
    @Override
    public String getPlanQueryString() {
        if (oldQuery) {
            return "EXPLAIN " + (verbose ? "VERBOSE " : "") + query;
        } else {
            Map<String, Object> parameters = this.configuration.getParameters();
            StringBuilder explainStat = new StringBuilder(64);
            explainStat.append("EXPLAIN (FORMAT XML");
            for (Map.Entry<String, Object> entry : CommonUtils.safeCollection(parameters.entrySet())) {
                String key = entry.getKey();
                if (PostgreQueryPlaner.PARAM_TIMING.equals(key)
                    && !CommonUtils.toBoolean(parameters.get(PostgreQueryPlaner.PARAM_ANALYSE))
                ) {
                    // We can't add TIMING if ANALYZE is not add
                    continue;
                }
                if (PostgreQueryPlaner.PARAM_COSTS.equals(key) || PostgreQueryPlaner.PARAM_TIMING.equals(key)) {
                    // COSTS and TIMING are true by default, no need to add it to query if they are true, but we need to add them
                    // if they are false. And SUMMARY is true by default only for ANALYZE parameter
                    if (!CommonUtils.toBoolean(entry.getValue())) {
                        explainStat.append(",").append(key).append(" FALSE");
                    }
                    continue;
                }
                // In other cases we can just add parameter keyword
                if (CommonUtils.toBoolean(entry.getValue())) {
                    explainStat.append(",").append(key);
                }
            }
            explainStat.append(") ").append(query);
            return explainStat.toString();
        }
    }

    @NotNull
    @Override
    public DBCPlanSourceFormat getPlanSourceDataFormat() {
        return oldQuery? DBCPlanSourceFormat.TEXT : DBCPlanSourceFormat.XML;
    }

    @Nullable
    @Override
    public Object getPlanSourceData() {
        return planText;
    }

    @NotNull
    @Override
    public List<? extends DBCPlanNode> getPlanNodes(@NotNull Map<String, Object> options)
    {
        return rootNodes;
    }

    public void explain(DBCSession session)
        throws DBCException
    {
        // A failed refresh must not expose the previous query's plan as a fresh result.
        rootNodes = new ArrayList<>();
        planText = null;
        JDBCSession connection = (JDBCSession) session;
        try (PlanTransaction transaction = new PlanTransaction(connection)) {
            try (JDBCStatement dbStat = connection.createStatement()) {
                try (JDBCResultSet dbResult = dbStat.executeQuery(getPlanQueryString())) {
                    if (oldQuery) {
                        List<String> planLines = new ArrayList<>();
                        while (dbResult.next()) {
                            String planLine = dbResult.getString(1);
                            if (planLine != null && !planLine.isBlank()) {
                                planLines.add(planLine);
                            }
                        }
                        parsePlanText(session, planLines);
                        planText = String.join("\n", planLines);
                    } else {
                        if (dbResult.next()) {
                            SQLXML planXML = dbResult.getSQLXML(1);
                            if (planXML == null) {
                                throw new DBCException("Server returned a null execution plan");
                            }
                            try (PlanXMLResource resource = new PlanXMLResource(planXML)) {
                                parsePlanXML(session, resource.xml());
                                planText = resource.xml().getString();
                            }
                        } else {
                            throw new DBCException("Server returned no execution plan");
                        }
                    }
                } catch (XMLException e) {
                    throw new DBCException("Can't parse plan XML", e);
                }
            }
        } catch (SQLException e) {
            rootNodes = new ArrayList<>();
            planText = null;
            throw new DBCException(e, session.getExecutionContext());
        }
    }

    /** Roll back only the analysis, never the user's pre-existing manual transaction. */
    private static final class PlanTransaction implements AutoCloseable {
        private final JDBCSession connection;
        private final Savepoint savepoint;

        private PlanTransaction(JDBCSession connection) throws SQLException {
            this.connection = connection;
            if (connection.getAutoCommit()) {
                connection.setAutoCommit(false);
                savepoint = null;
            } else {
                savepoint = connection.setSavepoint();
                if (savepoint == null) {
                    throw new SQLException("Cannot isolate execution plan analysis in the existing transaction");
                }
            }
        }

        @Override
        public void close() throws SQLException {
            // Do not enable auto-commit if rollback fails: that could commit analysis side effects.
            if (savepoint == null) {
                connection.rollback();
                connection.setAutoCommit(true);
            } else {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            }
        }
    }

    private record PlanXMLResource(SQLXML xml) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            xml.free();
        }
    }

    private void parsePlanXML(DBCSession session, SQLXML planXML) throws SQLException, XMLException, DBCException {
        rootNodes = new ArrayList<>();
        Document planDocument = XMLUtils.parseDocument(planXML.getBinaryStream());
        Element queryElement = XMLUtils.getChildElement(planDocument.getDocumentElement(), "Query");
        if (queryElement == null) {
            throw new DBCException("Execution plan XML contains no Query element");
        }
        for (Element planElement : XMLUtils.getChildElementList(queryElement, "Plan")) {
            rootNodes.add(new PostgrePlanNodeXML(session.getDataSource(), null, planElement));
        }
        if (rootNodes.isEmpty()) {
            throw new DBCException("Execution plan XML contains no Plan element");
        }
    }

    private void parsePlanText(DBCSession session, List<String> lines) throws DBCException {
        if (lines.isEmpty()) {
            throw new DBCException("Server returned no execution plan");
        }
        DBPDataSource dataSource = session.getDataSource();
        List<PostgrePlanNodeText> nodes = new ArrayList<>(lines.size());
        PostgrePlanNodeText rootNode = null, curNode = null, curParentNode = null;
        int curIndent = 0;
        for (String line : lines) {
            int lineIndent = 0;
            for (int i = lineIndent; i < line.length(); i++) {
                if (line.charAt(i) != ' ') {
                    break;
                }
                lineIndent++;
            }
            if (rootNode == null && lineIndent > 0) {
                throw new DBCException("Execution plan text contains no root node");
            }
            if (curIndent == 0 && lineIndent == 0) {
                // Root node
                curParentNode = curNode = rootNode = new PostgrePlanNodeText(dataSource, null, line, lineIndent);
                nodes.add(rootNode);
            } else if (lineIndent >= curIndent) {
                // Child node
                if (line.substring(lineIndent).startsWith(NODE_PREFIX)) {
                    //log.debug("New child " + line);
                    if (lineIndent > curNode.getIndent()) {
                        curParentNode = curNode;  
                    }
                    lineIndent += NODE_PREFIX.length();
                    curNode = new PostgrePlanNodeText(dataSource, curParentNode, line, lineIndent);
                    nodes.add(0,curNode);
                } else if (lineIndent == curIndent || lineIndent == curIndent) {
                    // Property
                    curNode.addProp(line);
                    continue;
                } else {
                    if (curNode != null) {
                        curNode.addProp(line);
                    } else {
                        log.debug("Unexpected node line: " + line);
                    }
                    continue;
                }
                curIndent = lineIndent;
            } else if (lineIndent < curIndent) {
                 if (lineIndent == 0) {
                    // Trailing plan statuses
                } else {
                     if (lineIndent + NODE_PREFIX.length() < curNode.getIndent()) {
                        //need find upper parent
                        curParentNode =  rootNode;
                        for(int i = 0;i<nodes.size();i++) {
                            if(nodes.get(i).getIndent() == lineIndent - 2) {
                                curParentNode = nodes.get(i);
                                break;
                            }
                        }
                    }
                    if (!line.substring(lineIndent).startsWith(NODE_PREFIX)) {
                        curNode.addProp(line);
                    } else {
                        lineIndent += NODE_PREFIX.length();
                        curNode = new PostgrePlanNodeText(dataSource, curParentNode, line, lineIndent);
                        nodes.add(0,curNode);
                    }
                }
                curIndent = lineIndent;
            }
        }
        rootNodes = new ArrayList<>();
        rootNodes.add(rootNode);
    }

}
