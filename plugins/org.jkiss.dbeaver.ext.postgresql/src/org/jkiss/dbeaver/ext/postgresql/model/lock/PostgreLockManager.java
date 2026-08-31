/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2017 Andrew Khitrin (ahitrin@gmail.com)
 * 				 2017 Dmitriy.Gurov (dvgurov@gmail.com) 
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
package org.jkiss.dbeaver.ext.postgresql.model.lock;

import org.jkiss.dbeaver.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.admin.locks.DBAServerLockManager;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.admin.locks.LockGraphManager;

import java.sql.SQLException;
import java.util.*;

/**
 * Postgres lock manager
 */

public class PostgreLockManager extends LockGraphManager implements DBAServerLockManager<PostgreLock,PostgreLockItem> {

	public static final String LOCK_QUERY =
        "with locks as ( "+
                    "select  "+
                     "pid,locktype, mode,granted,transactionid tid,relation,page,tuple "+
                    "from "+
                     "pg_locks "+
         "), "+
        "conflict(mode1,mode2,prt) as ( "+
                       "select 'AccessShareLock'::text,'AccessExclusiveLock'::text,1::integer "+
                       "union all select 'RowShareLock','ExclusiveLock',1 union all select 'RowShareLock','AccessExclusiveLock',2 "+
                       "union all select 'RowExclusiveLock','ShareLock',1 union all select 'RowExclusiveLock','ShareRowExclusiveLock',2 union all select 'RowExclusiveLock','ExclusiveLock',3 union all select 'RowExclusiveLock','AccessExclusiveLock',4 "+
                       "union all select 'ShareUpdateExclusiveLock','ShareUpdateExclusiveLock',1 union all select 'ShareUpdateExclusiveLock','ShareLock',2 union all select 'ShareUpdateExclusiveLock','ShareRowExclusiveLock',3 union all select 'ShareUpdateExclusiveLock','ExclusiveLock',4 union all select 'ShareUpdateExclusiveLock','AccessExclusiveLock',5 "+
                       "union all select 'ShareLock','RowExclusiveLock',1 union all select 'ShareLock','ShareUpdateExclusiveLock',2 union all select 'ShareLock','ShareRowExclusiveLock',3 union all select 'ShareLock','ExclusiveLock',4 union all select 'ShareLock','AccessExclusiveLock',5 "+
                       "union all select 'ShareRowExclusiveLock','RowExclusiveLock',1 union all select 'ShareRowExclusiveLock','ShareUpdateExclusiveLock',2 union all select 'ShareRowExclusiveLock','ShareLock',3 union all select 'ShareRowExclusiveLock','ShareRowExclusiveLock',4 union all select 'ShareRowExclusiveLock','ExclusiveLock',5 union all select 'ShareRowExclusiveLock','AccessExclusiveLock',6 "+
                       "union all select 'ExclusiveLock','RowShareLock',1 union all select 'ExclusiveLock','RowExclusiveLock',2 union all select 'ExclusiveLock','ShareUpdateExclusiveLock',3 union all select 'ExclusiveLock','ShareLock',4 union all select 'ExclusiveLock','ShareRowExclusiveLock',5 union all select 'ExclusiveLock','ExclusiveLock',6 union all select 'ExclusiveLock','AccessExclusiveLock',7 "+
                       "union all select 'AccessExclusiveLock','AccessShareLock',1 union all select 'AccessExclusiveLock','RowShareLock',2 union all select 'AccessExclusiveLock','RowExclusiveLock',3 union all select 'AccessExclusiveLock','ShareUpdateExclusiveLock',4 union all select 'AccessExclusiveLock','ShareLock',5 union all select 'AccessExclusiveLock','ShareRowExclusiveLock',6 union all select 'AccessExclusiveLock','ExclusiveLock',7 union all select 'AccessExclusiveLock','AccessExclusiveLock',8 "+
        ")	  "+
        ",real_locks as ("+
        "select 	  "+
        "la.pid as blocked_pid, "+
        "blocked_activity.usename  AS blocked_user, "+
        "la.blocked     AS blocking_pid, "+
        "blocking_activity.usename AS blocking_user, "+
        "blocked_activity.query    AS blocked_statement, "+
        "blocking_activity.query   AS statement_in "+
        "from  "+
        "( "+
        "	select 				 "+
        "l.*, "+
        "c.mode2, "+
        "c.prt, "+
        "l2.pid blocked, "+
        "row_number() over(partition by l.pid order by c.prt) rid "+
        "from   "+
        "locks l "+
        "join conflict c on l.mode = c.mode1 "+
        "join locks l2 on l2.locktype = l.locktype and l2.mode = c.mode2 and l2.granted and l.pid != l2.pid and  "+
                                  "concat(coalesce(l.tid::text,'*'),':',coalesce(l.relation::text,'*'),':',coalesce(l.page::text,'*'),':',coalesce(l.tuple::text,'*')) = "+
                                  "concat(coalesce(l2.tid::text,'*'),':',coalesce(l2.relation::text,'*'),':',coalesce(l2.page::text,'*'),':',coalesce(l2.tuple::text,'*')) "+
        "where not l.granted "+
        ") la "+
        "join pg_catalog.pg_stat_activity blocked_activity  ON blocked_activity.pid = la.pid "+
        "join pg_catalog.pg_stat_activity blocking_activity  ON blocking_activity.pid = la.blocked "+
        "where la.rid = 1) "+

            ", root_quest as ( "+
            "   select distinct r.blocking_pid from real_locks r "+
            "   where not exists (select 1 from real_locks b where b.blocked_pid = r.blocking_pid)) "+
            " select blocked_pid, "+
            "       blocked_user, "+
             "      blocking_pid, "+
              "     blocking_user, "+
               "    blocked_statement, "+
                "   statement_in "+
                " from"+
                 "  real_locks"+
            " union"+
            " select"+
                   " real_locks.blocking_pid, "+
                   " real_locks.blocking_user, "+
                   " null::integer, "+
                   " null::text, "+
                   " real_locks.statement_in, "+
                   " null::text "+
                " from"+
                   " real_locks, "+
                   " root_quest "+
                " where"+
                   " real_locks.blocking_pid = root_quest.blocking_pid "
											;

	public static final String LOCK_ITEM_QUERY = "select "+
			" coalesce(db.datname::text,'') as datname, "+
			" coalesce(lck.locktype,'') as locktype, "+
			" coalesce(lck.relation::regclass::varchar,'') as relation, "+
			" coalesce(lck.mode,'') as mode, "+
			" coalesce(lck.transactionid::varchar,'') as tid, "+
			" lck.page as page, "+
			" lck.tuple as tuple, "+
			" lck.pid as pid, "+
			" lck.granted"+
			" from pg_catalog.pg_locks lck "+
			"   left join pg_catalog.pg_database db "+
			"     on db.oid = lck.database "+
			" where  "+
			"  lck.pid = ? ";
    public static final String pidHold = "hpid";
    public static final String pidWait = "wpid";


    private final PostgreDataSource dataSource;

    public PostgreLockManager(PostgreDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public Map<Object, PostgreLock> getLocks(DBCSession session, Map<String, Object> options) throws DBException {
        try {

            Map<Object, PostgreLock> locks = new HashMap<>(10);

            try (JDBCPreparedStatement dbStat = ((JDBCSession) session).prepareStatement(LOCK_QUERY)) {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {

                    while (dbResult.next()) {
                        PostgreLock l = new PostgreLock(dbResult);
                        locks.put(l.getId(), l);
                    }
                }

            }

            super.buildGraphs(locks);
            return locks;

        } catch (SQLException e) {
            throw new DBDatabaseException(e, session.getDataSource());
        }

    }

    @Override
    public void alterSession(DBCSession session, PostgreLock lock, Map<String, Object> options) throws DBException {
        try {
            try (JDBCPreparedStatement dbStat = ((JDBCSession) session).prepareStatement("SELECT pg_catalog.pg_terminate_backend(?)")) {
                dbStat.setInt(1, lock.getWait_pid());
                dbStat.execute();
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, session.getDataSource());
        }
    }

    @Override
    public Class<PostgreLock> getLocksType() {
        return PostgreLock.class;
    }


    @Override
    public Collection<PostgreLockItem> getLockItems(DBCSession session, Map<String, Object> options)
        throws DBException {
        try {

            List<PostgreLockItem> locks = new ArrayList<>();

            try (JDBCPreparedStatement dbStat = ((JDBCSession) session).prepareStatement(LOCK_ITEM_QUERY)) {

                String otype = (String) options.get(LockGraphManager.keyType);

                switch (otype) {

                    case LockGraphManager.typeWait:
                        dbStat.setInt(1, (int) options.get(pidWait));
                        break;

                    case LockGraphManager.typeHold:
                        dbStat.setInt(1, (int) options.get(pidHold));
                        break;

                    default:
                        return locks;
                }

                try (JDBCResultSet dbResult = dbStat.executeQuery()) {

                    while (dbResult.next()) {
                        locks.add(new PostgreLockItem(dbResult));
                    }
                }
            }

            return locks;

        } catch (SQLException e) {
            throw new DBDatabaseException(e, session.getDataSource());
        }
    }

}
