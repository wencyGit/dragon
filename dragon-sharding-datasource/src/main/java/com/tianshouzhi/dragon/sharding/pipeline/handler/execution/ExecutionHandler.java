package com.tianshouzhi.dragon.sharding.pipeline.handler.execution;

import com.tianshouzhi.dragon.sharding.jdbc.connection.DragonShardingConnection;
import com.tianshouzhi.dragon.sharding.pipeline.Handler;
import com.tianshouzhi.dragon.sharding.pipeline.HandlerContext;
import com.tianshouzhi.dragon.sharding.pipeline.handler.sqlrewrite.SqlRouteInfo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Created by TIANSHOUZHI336 on 2016/12/11.
 */
public class ExecutionHandler implements Handler {

    @Override
    public void invoke(HandlerContext context) throws SQLException {
        long start=System.currentTimeMillis();
        //判断是否开启了事务，如果开启了事务，sql只能路由到一个库中
        DragonShardingConnection shardingConnection = context.getDragonShardingStatement().getConnection();
        boolean autoCommit = shardingConnection.getAutoCommit();
        Map<String, Map<String, SqlRouteInfo>> sqlRouteMap = context.getSqlRouteMap();

        ExecutorService executor = DragonExecutorFactory.getInstance(context.getLogicDataSource().getNameFormat());
        CompletionService<String> ecs = new ExecutorCompletionService<String>(executor);
        int taskNum=0;

        try {
            //因为一个connection可以创建多个statement，在执行当前statement的时候，判断之前的statement中使用的connection在这里是否可以复用
            Map<String, Set<Connection>> realConnectionMap = shardingConnection.getRealConnectionMap();
            if(autoCommit){
                for (Map.Entry<String, Map<String, SqlRouteInfo>> entry : sqlRouteMap.entrySet()) {
                    String realDBName = entry.getKey();

                    //尝试复用connection，因为之前这个connection执行过statment的话，其肯定包含部分真实connection的引用
                    Connection connection=null;
                    if(realConnectionMap.containsKey(realDBName)){//fixme 这样会导致一直只用一个connection
                        connection=realConnectionMap.get(realDBName).iterator().next();
                    }
                    final DataSource ds = context.getRealDataSource(realDBName);

                    if (autoCommit) {//不开启事务 每个sql各自使用一个连接去执行，不管操作的表是不是位于同一个库中，并行执行效率高
                        Map<String, SqlRouteInfo> tableSqlMap = entry.getValue();
                        Iterator<Map.Entry<String, SqlRouteInfo>> iterator = tableSqlMap.entrySet().iterator();
                        while (iterator.hasNext()) {
                            Map.Entry<String, SqlRouteInfo> next = iterator.next();
                            final SqlRouteInfo sqlRouteInfo = next.getValue();
                            ExecutionTask sqlExecutionTask = new ExecutionTask(connection,ds, autoCommit, sqlRouteInfo);
                            Future<String> future = ecs.submit(sqlExecutionTask);
                            taskNum++;
                        }
                    }
                }
            }else{//如果开启事务，同一个库的所有sql使用一个连接
                if(!autoCommit&&sqlRouteMap.size()>1){//多个库肯定不能使用事务
                    String originSql = context.getDragonShardingStatement().getSql();
                    Set<String> dbNames = context.getSqlRouteMap().keySet();
                    throw new UnsupportedOperationException("only support transaction in one db,sql:"+ originSql+" route to :"+ dbNames);
                }
                Map.Entry<String, Map<String, SqlRouteInfo>> next = sqlRouteMap.entrySet().iterator().next();
                String realDBName = next.getKey();
                final DataSource ds = context.getRealDataSource(realDBName);
                Connection connection=null;
                if(realConnectionMap.containsKey(realDBName)){//总是拿第一个connection当做事务连接
                    connection=realConnectionMap.get(realDBName).iterator().next();
                }
                Collection<SqlRouteInfo> values = next.getValue().values();
                SqlRouteInfo[] sqlRouteInfos=new SqlRouteInfo[values.size()];
                values.toArray(sqlRouteInfos);
                ExecutionTask sqlExecutionTask = new ExecutionTask(connection,ds, autoCommit, sqlRouteInfos);
                Future<String> future = ecs.submit(sqlExecutionTask);
                taskNum++;
            }

            context.setParallelExecutionTaskNum(taskNum);
            //等待所有的task执行完成
            for (int i = 0; i < taskNum; i++) {
                ecs.take().get();
            }

            //将真实connection封装到sharding connection中 // TODO: 2017/3/19 是否需要精确到表
            for (Map.Entry<String, Map<String, SqlRouteInfo>> mapEntry : sqlRouteMap.entrySet()) {
                String realDBName = mapEntry.getKey();
                for (SqlRouteInfo routeInfo : mapEntry.getValue().values()) {
                    PreparedStatement targetStatement = routeInfo.getTargetStatement();
                    Connection connection = targetStatement.getConnection();
                    Set<Connection> connections = realConnectionMap.get(realDBName);
                    if(connections==null){
                        connections=new HashSet<Connection>();
                    }
                    connections.add(connection);
                    realConnectionMap.put(realDBName,connections);
                }
            }
            context.setParallelExecutionTimeMillis(System.currentTimeMillis()-start);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}