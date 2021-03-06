package com.tianshouzhi.dragon.ha.exception;

import com.tianshouzhi.dragon.common.log.Log;
import com.tianshouzhi.dragon.common.log.LoggerFactory;
import com.tianshouzhi.dragon.common.thread.DragonThreadFactory;
import com.tianshouzhi.dragon.ha.jdbc.datasource.RealDataSourceWrapper;
import com.tianshouzhi.dragon.ha.util.DatasourceUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Created by tianshouzhi on 2017/9/22.
 */
public abstract class DataSourceMonitor {
	private static final Log LOG = LoggerFactory.getLogger(DataSourceMonitor.class);
    public static final String CHECK_THREAD_NAME ="DRAGON_FATAL_EXCEPTION_DATASOURCE_CHECKER";
	public static Map<String, Set<RealDataSourceWrapper>> unavailableDataSources = new ConcurrentHashMap<String, Set<RealDataSourceWrapper>>();

	private static ScheduledExecutorService monitorExecutor =Executors.newSingleThreadScheduledExecutor(
	      new DragonThreadFactory(CHECK_THREAD_NAME, true));

	static {
		monitorExecutor.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				if (unavailableDataSources != null) {
					for (Map.Entry<String, Set<RealDataSourceWrapper>> haDatasourceEntry : unavailableDataSources
					      .entrySet()) {
						for (RealDataSourceWrapper dataSourceWrapper : haDatasourceEntry.getValue()) {
							try {
								if (canMarkup(dataSourceWrapper)) {
									markup(dataSourceWrapper);
								}
							} catch (Exception ignore) {
								// continue for next
							}
						}
					}
				}
			}
		}, 1, 1, TimeUnit.SECONDS);
	}

	private static boolean canMarkup(RealDataSourceWrapper dataSourceWrapper)  {
		boolean markup = false;
        Connection connection =null;
        try{
            connection = dataSourceWrapper.getConnection();
            
            if(connection.isValid(3)){
                markup=true;
            }
            
            //如果拥有show slave status执行权限，
            PreparedStatement preparedStatement = connection.prepareStatement("show slave status");
            ResultSet resultSet = preparedStatement.executeQuery();
            boolean slaveIoRunning = "Yes".equalsIgnoreCase(resultSet.getString("Slave_IO_Running"));// slave IO 线程在运行
            boolean slaveSqlRunning = "Yes".equalsIgnoreCase(resultSet.getString("Slave_SQL_Running")); // slave SQL线程在运行
            boolean noDelay = resultSet.getInt("Seconds_Behind_Master")==0;// 主从同步没有延迟
			if (!(slaveIoRunning && slaveSqlRunning && noDelay)) {
				LOG.warn("the db which "+dataSourceWrapper.getFullName() +"connected to is recovery,wait sync the " +
						"master binlog to markup" );
				markup = false;
			}
            resultSet.close();
            preparedStatement.close();
        }catch (Exception ignore){}
        DatasourceUtil.close(connection);
		return markup;
	}

	private static void markup(RealDataSourceWrapper dataSourceWrapper) {
		dataSourceWrapper.enable();
        Set<RealDataSourceWrapper> invalidSet = unavailableDataSources.get(dataSourceWrapper.getHaDSName());
        invalidSet.remove(dataSourceWrapper);
        LOG.info("markup real datasource 【" + dataSourceWrapper.getFullName() + "】!!!");
	}

	public static boolean monitor(SQLException e, RealDataSourceWrapper dataSourceWrapper) {
		boolean fatal = ExceptionSorterUtil.isExceptionFatal(e);
		if (fatal) {
			markdown(dataSourceWrapper);
		}
		return fatal;
	}

	private static void markdown(RealDataSourceWrapper dataSourceWrapper) {
		dataSourceWrapper.disable();
        Set<RealDataSourceWrapper> invalidSet = unavailableDataSources.get(dataSourceWrapper.getHaDSName());
        if (invalidSet == null) {
            synchronized (DataSourceMonitor.class) {
                if (invalidSet == null) {
                    invalidSet = new ConcurrentSkipListSet<RealDataSourceWrapper>();
                    unavailableDataSources.put(dataSourceWrapper.getHaDSName(), invalidSet);
                }
            }
        }
        invalidSet.add(dataSourceWrapper);
		LOG.warn("markdown real datasource 【" + dataSourceWrapper.getFullName() + "】!!!");
	}

	public static boolean isAvailable(RealDataSourceWrapper realDataSourceWrapper) {
		Set<RealDataSourceWrapper> invalidSet = unavailableDataSources.get(realDataSourceWrapper.getHaDSName());
		if (invalidSet == null || !invalidSet.contains(realDataSourceWrapper)) {
			return true;
		}
		return false;
	}

	public static Set<String> getInvalidRealDs(String hsDSName) {
		Set<RealDataSourceWrapper> invalidSet = unavailableDataSources.get(hsDSName);
		if (invalidSet == null) {
			return null;
		}
		Set<String> invalidNames = new HashSet<String>(4);
		for (RealDataSourceWrapper invalid : invalidSet) {
			invalidNames.add(invalid.getDsName());
		}
		return invalidNames;
	}
}
