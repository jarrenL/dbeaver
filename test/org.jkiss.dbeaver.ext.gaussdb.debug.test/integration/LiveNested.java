/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * SPDX-License-Identifier: Apache-2.0
 */
import java.sql.*;
import java.nio.file.*;
import java.util.concurrent.*;
public class LiveNested extends LiveDebug {
 static String frameLocal(Connection c,int frame,String name)throws Exception {
  try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM DBE_PLDEBUGGER.info_locals("+frame+")")){
   while(r.next())if(name.equals(r.getString("varname")))return r.getString("value");
  }return null;
 }
 public static void main(String[] args)throws Exception {
  try(var r=Files.newBufferedReader(Path.of(args[0]))){props.load(r);}
  props.setProperty("socketTimeout","20");Class.forName(props.getProperty("review.driverClass","com.huawei.gaussdb.jdbc.Driver"));
  for(String mode:new String[]{"ora","mysql","pg"}) {
   prefix=mode+"/nested";String db="dbeaver_fix_0905_"+mode;
   try(Connection t=open(db,true);Connection c=open(db,true)) {
    exec(t,"CREATE OR REPLACE PROCEDURE fix.fix_inner() AS DECLARE\nv_local integer := 222;\nBEGIN\nv_local := v_local + 1;\nv_local := v_local + 2;\nEND;");
    exec(t,"CREATE OR REPLACE PROCEDURE fix.fix_outer() AS DECLARE\nv_local integer := 111;\nBEGIN\nfix.fix_inner();\nv_local := v_local + 1;\nEND;");
    long oid=Long.parseLong(scalar(t,"SELECT oid FROM pg_proc WHERE proname='fix_outer'"));
    t.setAutoCommit(false);
    String node;int port;
    try(Statement s=t.createStatement();ResultSet r=s.executeQuery("SELECT * FROM DBE_PLDEBUGGER.turn_on("+oid+"::oid)")){r.next();node=r.getString(1);port=r.getInt(2);}
    ExecutorService pool=Executors.newSingleThreadExecutor();
    Future<?> job=pool.submit(()->{try{exec(t,"CALL fix.fix_outer()");}catch(Exception e){throw new RuntimeException(e);}});
    try {
     try(PreparedStatement s=c.prepareStatement("SELECT * FROM DBE_PLDEBUGGER.attach(?, ?)")) {
      s.setString(1,node);s.setInt(2,port);s.setQueryTimeout(10);
      for(int i=0;;i++){try{s.execute();break;}catch(SQLException e){if(!"D0011".equals(e.getSQLState())||i>100)throw e;}Thread.sleep(50);}
     }
     control(c,"step");
     check("2".equals(scalar(c,"SELECT count(*) FROM DBE_PLDEBUGGER.backtrace()")),"step enters nested procedure and shows two frames");
     check("222".equals(frameLocal(c,0,"v_local")),"top frame variable");
     check("111".equals(frameLocal(c,1,"v_local")),"caller frame variable");
     check(set(c,"v_local","999"),"set_var accepted on current frame");
     check("999".equals(frameLocal(c,0,"v_local"))&&"111".equals(frameLocal(c,1,"v_local")),"set_var affects current frame only");
     String finishResponse=control(c,"finish"); System.out.println("FINISH RESPONSE: "+finishResponse);
     if(finishResponse.contains("ERROR")) {
      System.out.println("AFTER ERROR CONTINUE: "+control(c,"continue"));
      try{job.get(5,TimeUnit.SECONDS);}catch(Exception e){System.out.println("TARGET ERROR DETAIL: "+e);}
      throw new AssertionError("finish returned execution error");
     }
     check("1".equals(scalar(c,"SELECT count(*) FROM DBE_PLDEBUGGER.backtrace()")),"finish returns to caller");
     String response=control(c,"continue");
     check(response.contains("[EXECUTION FINISHED]"),"outer procedure completes");
     job.get(10,TimeUnit.SECONDS);t.rollback();
     exec(t,"SELECT DBE_PLDEBUGGER.turn_off("+oid+"::oid)");t.rollback();
    }finally{if(!job.isDone())try{exec(c,"SELECT DBE_PLDEBUGGER.abort()");}catch(Exception ignored){}pool.shutdownNow();}
   }
  }
  System.out.println("TOTAL NESTED CHECKS "+checks);
 }
}
