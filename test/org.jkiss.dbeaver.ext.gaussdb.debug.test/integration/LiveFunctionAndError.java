/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
import java.sql.*;
import java.nio.file.*;
import java.util.concurrent.*;
public class LiveFunctionAndError extends LiveDebug {
 public static void main(String[] args)throws Exception {
  try(var r=Files.newBufferedReader(Path.of(args[0]))){props.load(r);}
  props.setProperty("socketTimeout","20");Class.forName("com.huawei.gaussdb.jdbc.Driver");
  for(String mode:new String[]{"ora","mysql","pg"})for(boolean error:new boolean[]{false,true}){
   prefix=mode+(error?"/runtime-error":"/function");String db="dbeaver_fix_0905_"+mode;
   try(Connection setup=open(db,true);Connection t=open(db,true);Connection c=open(db,true)) {
    exec(setup,"TRUNCATE fix.fix_audit");
    exec(setup,"CREATE OR REPLACE FUNCTION fix.fix_function(p_in integer) RETURNS integer AS $$ DECLARE v integer; BEGIN v := p_in + 1; RETURN v; END; $$ LANGUAGE plpgsql;");
    exec(setup,"CREATE OR REPLACE PROCEDURE fix.fix_error(p_in integer) AS DECLARE\nv integer;\nBEGIN\nINSERT INTO fix.fix_audit VALUES(999);\nv := 1 / p_in;\nEND;");
    String name=error?"fix_error":"fix_function";
    long oid=Long.parseLong(scalar(setup,"SELECT oid FROM pg_proc WHERE proname='"+name+"'"));
    t.setAutoCommit(false);
    String node;int port;
    try(Statement s=t.createStatement();ResultSet r=s.executeQuery("SELECT * FROM DBE_PLDEBUGGER.turn_on("+oid+"::oid)")){r.next();node=r.getString(1);port=r.getInt(2);}
    ExecutorService pool=Executors.newSingleThreadExecutor();
    Future<String> job=pool.submit(()->{
     try(PreparedStatement s=t.prepareStatement((error?"CALL fix.fix_error":"SELECT fix.fix_function")+"(?::integer)")){
      s.setString(1,error?"0":"6");boolean has=s.execute();
      if(has)try(ResultSet r=s.getResultSet()){return r.next()?r.getString(1):null;}
      return null;
     }
    });
    try {
     try(PreparedStatement s=c.prepareStatement("SELECT * FROM DBE_PLDEBUGGER.attach(?, ?)")){
      s.setString(1,node);s.setInt(2,port);s.setQueryTimeout(10);
      for(int i=0;;i++){try{s.execute();break;}catch(SQLException e){if(!"D0011".equals(e.getSQLState())||i>100)throw e;}Thread.sleep(50);}
     }
     String response=control(c,"continue");
     if(error){
      check(response.contains("[EXECUTION HAS ERROR OCCURRED!]"),"runtime error marker");
      t.close();
      try{job.get(10,TimeUnit.SECONDS);throw new AssertionError("expected target failure");}catch(ExecutionException expected){check(true,"closing target releases runtime-error wait");}
      check("0".equals(scalar(setup,"SELECT count(*) FROM fix.fix_audit")),"writes before runtime error rolled back");
     }else{
      check(response.contains("[EXECUTION FINISHED]"),"function debugger finishes");
      check("7".equals(job.get(10,TimeUnit.SECONDS)),"typed SELECT function returns 7");t.rollback();
     }
     if(!t.isClosed()){exec(t,"SELECT DBE_PLDEBUGGER.turn_off("+oid+"::oid)");t.rollback();}
    }finally{if(!job.isDone())try{exec(c,"SELECT DBE_PLDEBUGGER.abort()");}catch(Exception ignored){}pool.shutdownNow();}
   }
  }
  System.out.println("TOTAL FUNCTION/ERROR CHECKS "+checks);
 }
}
