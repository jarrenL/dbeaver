/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class LiveDebug {
    static Properties props = new Properties();
    static int checks;
    static String prefix;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + prefix + " " + message);
    }
    static Connection open(String db, boolean nativeDriver) throws Exception {
        return DriverManager.getConnection((nativeDriver ? "jdbc:gaussdb:" : "jdbc:postgresql:") + "//127.0.0.1:5432/" + db, props);
    }
    static void exec(Connection c, String sql) throws Exception {
        try (Statement s=c.createStatement()) {s.setQueryTimeout(15);s.execute(sql);}
    }
    static String scalar(Connection c,String sql) throws Exception {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)) {return r.next()?r.getString(1):null;}
    }
    static boolean set(Connection c,String name,String value) throws Exception {
        try(PreparedStatement s=c.prepareStatement("SELECT DBE_PLDEBUGGER.set_var(?, ?)")) {
            s.setString(1,name);s.setString(2,value);
            try(ResultSet r=s.executeQuery()){return r.next()&&r.getBoolean(1);}
        }
    }
    static String local(Connection c,String name) throws Exception {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM DBE_PLDEBUGGER.info_locals(0)")) {
            while(r.next())if(name.equals(r.getString("varname")))return r.getString("value");
            throw new AssertionError("No variable "+name);
        }
    }
    static int add(Connection c,long oid,int line) throws Exception {
        try(PreparedStatement s=c.prepareStatement("SELECT DBE_PLDEBUGGER.add_breakpoint(?::oid, ?::integer)")) {
            s.setString(1,Long.toString(oid));s.setInt(2,line);
            try(ResultSet r=s.executeQuery()){r.next();return r.getInt(1);}
        }
    }
    static String control(Connection c,String command) throws Exception {
        try(Statement s=c.createStatement()){s.setQueryTimeout(15);
            try(ResultSet r=s.executeQuery("SELECT * FROM DBE_PLDEBUGGER."+command+"()")) {
                return r.next()?r.getString("query"):null;
            }
        }
    }
    static void run(String mode,boolean nativeDriver,boolean commit,boolean abort) throws Exception {
        String db="dbeaver_fix_0905_"+mode;
        prefix=mode+"/"+(nativeDriver?"native":"postgresql")+"/"+(abort?"abort":commit?"commit":"rollback");
        try(Connection setup=open(db,nativeDriver)) {
            if ("0".equals(scalar(setup,"SELECT count(*) FROM pg_namespace WHERE nspname='fix'"))) exec(setup,"CREATE SCHEMA fix");
            exec(setup,"CREATE TABLE IF NOT EXISTS fix.fix_audit (n integer)");
            exec(setup,"TRUNCATE fix.fix_audit");
            exec(setup,"CREATE OR REPLACE PROCEDURE fix.fix_target(p_in integer) AS DECLARE\n"
                +"v_local integer := 0;\nv_const constant integer := 100;\nv_text text := 'initial';\n"
                +"BEGIN\nv_local := p_in + 1;\nv_text := 'PASSWORD business';\nINSERT INTO fix.fix_audit VALUES(v_local);\nv_local := v_local + 10;\nEND;");
            long oid=Long.parseLong(scalar(setup,"SELECT oid FROM pg_proc WHERE proname='fix_target'"));
            try(Connection target=open(db,nativeDriver);Connection controller=open(db,nativeDriver)) {
                target.setAutoCommit(false);
                String node;int port;
                try(PreparedStatement s=target.prepareStatement("SELECT * FROM DBE_PLDEBUGGER.turn_on(?::oid)")) {
                    s.setLong(1,oid);try(ResultSet r=s.executeQuery()){r.next();node=r.getString(1);port=r.getInt(2);}
                }
                ExecutorService pool=Executors.newSingleThreadExecutor();
                Future<?> job=pool.submit(()-> {try {exec(target,"CALL fix.fix_target(6)");}catch(Exception e){System.err.println("TARGET FAILURE: "+e.getMessage());throw new RuntimeException(e);}});
                try {
                    try(PreparedStatement s=controller.prepareStatement("SELECT * FROM DBE_PLDEBUGGER.attach(?, ?)")) {
                        s.setQueryTimeout(10);s.setString(1,node);s.setInt(2,port);
                        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                        while(true){try{s.execute();break;}catch(SQLException e){if(!"D0011".equals(e.getSQLState())||System.nanoTime()>=deadline)throw e;}Thread.sleep(50);}
                    }
                    check("0".equals(local(controller,"v_local")),"attach and initial locals");
                    check(!set(controller,"v_local","not_an_integer"),"invalid integer rejected");
                    check("0".equals(local(controller,"v_local")),"rejected value unchanged");
                    check(set(controller,"v_local","4 + 5"),"expression accepted");
                    check("9".equals(local(controller,"v_local")),"expression reads back as 9");
                    check(set(controller,"v_text","'quote '' and PASSWORD business'"),"quoted text accepted");
                    check("quote ' and PASSWORD business".equals(local(controller,"v_text")),"text reads back normalized");
                    check(set(controller,"v_text","NULL"),"NULL expression accepted");
                    String nullValue = local(controller,"v_text"); System.out.println("SERVER NULL REPRESENTATION: ["+nullValue+"]"); check(nullValue==null || "NULL".equals(nullValue) || "<UNKNOWN>".equals(nullValue),"NULL reads back server null marker");
                    check(!set(controller,"v_const","1"),"constant rejected");
                    List<Integer> lines=new ArrayList<>();
                    try(Statement s=controller.createStatement();ResultSet r=s.executeQuery("SELECT * FROM DBE_PLDEBUGGER.info_code("+oid+"::oid)")) {
                        while(r.next())if(r.getBoolean("canbreak"))lines.add(r.getInt("lineno"));
                    }
                    check(lines.size()>=3,"executable source lines");
                    int line=lines.get(1);
                    int id=add(controller,oid,line);check(id>=0,"typed add breakpoint");
                    check(add(controller,oid,line)<0,"duplicate rejected by server");
                    exec(controller,"SELECT DBE_PLDEBUGGER.disable_breakpoint("+id+")");
                    exec(controller,"SELECT DBE_PLDEBUGGER.enable_breakpoint("+id+")");
                    exec(controller,"SELECT DBE_PLDEBUGGER.delete_breakpoint("+id+")");
                    id=add(controller,oid,line);check(id>=0,"delete then re-register breakpoint");
                    check(scalar(controller,"SELECT count(*) FROM DBE_PLDEBUGGER.info_breakpoints()") .equals("1"),"one registered breakpoint");
                    if(abort) {
                        exec(controller,"SELECT DBE_PLDEBUGGER.abort()");
                        try{job.get(15,TimeUnit.SECONDS);}catch(ExecutionException expected){}
                        target.rollback();
                        check("0".equals(scalar(setup,"SELECT count(*) FROM fix.fix_audit")),"abort leaves no data");
                    } else {
                        check(!control(controller,"step").contains("[EXECUTION FINISHED]"),"step stops in routine");
                        check(Integer.parseInt(scalar(controller,"SELECT count(*) FROM DBE_PLDEBUGGER.backtrace()"))>=1,"backtrace available");
                        check(!control(controller,"next").contains("[EXECUTION FINISHED]"),"next stops in routine");
                        String response=control(controller,"continue");
                        for(int i=0;i<10&&!response.contains("[EXECUTION FINISHED]");i++) response=control(controller,"continue");
                        check(response.contains("[EXECUTION FINISHED]"),"continue finishes");
                        job.get(15,TimeUnit.SECONDS);
                        if(commit)target.commit();else target.rollback();
                        check((commit?"1":"0").equals(scalar(setup,"SELECT count(*) FROM fix.fix_audit")),"transaction outcome visible on independent connection");
                    }
                    exec(target,"SELECT DBE_PLDEBUGGER.turn_off("+oid+"::oid)");target.rollback();
                } finally {
                    if(!job.isDone()) {
                        try{exec(controller,"SELECT DBE_PLDEBUGGER.abort()");}catch(Exception ignored){}
                    }
                    pool.shutdownNow();
                }
            }
        }
    }
    public static void main(String[] args)throws Exception {
        try(var r=Files.newBufferedReader(Path.of(args[0]))){props.load(r);}
        props.setProperty("socketTimeout","20");props.setProperty("connectTimeout","10");
        boolean nativeDriver = args.length < 2 || !"--postgresql".equals(args[1]);
        Class.forName(nativeDriver ? "com.huawei.gaussdb.jdbc.Driver" : "org.postgresql.Driver");
        for(String mode:List.of("ora","mysql","pg")) {
            run(mode,nativeDriver,false,false);
            run(mode,nativeDriver,true,false);
            run(mode,nativeDriver,false,true);
        }
        System.out.println("TOTAL CHECKS "+checks);
    }
}
