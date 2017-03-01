/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.core.query.executor;

import com.linkedin.pinot.common.data.DataManager;
import com.linkedin.pinot.common.exception.QueryException;
import com.linkedin.pinot.common.metrics.ServerMeter;
import com.linkedin.pinot.common.metrics.ServerMetrics;
import com.linkedin.pinot.common.metrics.ServerQueryPhase;
import com.linkedin.pinot.common.query.QueryExecutor;
import com.linkedin.pinot.common.query.QueryRequest;
import com.linkedin.pinot.common.query.context.TimerContext;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.request.InstanceRequest;
import com.linkedin.pinot.common.utils.DataTable;
import com.linkedin.pinot.core.common.datatable.DataTableImplV2;
import com.linkedin.pinot.core.data.manager.offline.InstanceDataManager;
import com.linkedin.pinot.core.data.manager.offline.SegmentDataManager;
import com.linkedin.pinot.core.data.manager.offline.TableDataManager;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.plan.Plan;
import com.linkedin.pinot.core.plan.maker.InstancePlanMakerImplV2;
import com.linkedin.pinot.core.plan.maker.PlanMaker;
import com.linkedin.pinot.core.query.config.QueryExecutorConfig;
import com.linkedin.pinot.core.query.pruner.SegmentPrunerService;
import com.linkedin.pinot.core.query.pruner.SegmentPrunerServiceImpl;
import com.linkedin.pinot.core.util.trace.TraceContext;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ServerQueryExecutorV1Impl implements QueryExecutor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerQueryExecutorV1Impl.class);

  private InstanceDataManager _instanceDataManager = null;
  private SegmentPrunerService _segmentPrunerService = null;
  private PlanMaker _planMaker = null;
  private volatile boolean _isStarted = false;
  private long _defaultTimeOutMs = 15000;
  private boolean _printQueryPlan = false;
  private final Map<String, Long> _resourceTimeOutMsMap = new ConcurrentHashMap<String, Long>();
  private ServerMetrics _serverMetrics;
  private int _totalRawDocs = 0;

  public ServerQueryExecutorV1Impl() {
  }

  public ServerQueryExecutorV1Impl(boolean printQueryPlan) {
    _printQueryPlan = printQueryPlan;
  }

  @Override
  public void init(Configuration configuration, DataManager dataManager, ServerMetrics serverMetrics)
      throws ConfigurationException {
    _serverMetrics = serverMetrics;
    _instanceDataManager = (InstanceDataManager) dataManager;
    QueryExecutorConfig queryExecutorConfig = new QueryExecutorConfig(configuration);
    if (queryExecutorConfig.getTimeOut() > 0) {
      _defaultTimeOutMs = queryExecutorConfig.getTimeOut();
    }
    LOGGER.info("Default timeout for query executor : {}", _defaultTimeOutMs);
    LOGGER.info("Trying to build SegmentPrunerService");
    _segmentPrunerService = new SegmentPrunerServiceImpl(queryExecutorConfig.getPrunerConfig());
    LOGGER.info("Trying to build QueryPlanMaker");
    _planMaker = new InstancePlanMakerImplV2(queryExecutorConfig);
    LOGGER.info("Trying to build QueryExecutorTimer");
  }

  @Override
  public DataTable processQuery(final QueryRequest queryRequest, ExecutorService executorService) {
    TimerContext timerContext = queryRequest.getTimerContext();
    TimerContext.Timer schedulerWaitTimer = timerContext.getPhaseTimer(ServerQueryPhase.SCHEDULER_WAIT);
    if (schedulerWaitTimer != null) {
      schedulerWaitTimer.stopAndRecord();
    }
    TimerContext.Timer queryProcessingTimer = timerContext.startNewPhaseTimer(ServerQueryPhase.QUERY_PROCESSING);

    DataTable dataTable;
    List<SegmentDataManager> queryableSegmentDataManagerList = null;
    InstanceRequest instanceRequest = queryRequest.getInstanceRequest();
    final long requestId = instanceRequest.getRequestId();
    final long nSegmentsInQuery = instanceRequest.getSearchSegmentsSize();
    try {
      TraceContext.register(instanceRequest);
      final BrokerRequest brokerRequest = instanceRequest.getQuery();
      LOGGER.debug("Incoming query is : {}", brokerRequest);

      TimerContext.Timer segmentPruneTimer = timerContext.startNewPhaseTimer(ServerQueryPhase.SEGMENT_PRUNING);
      queryableSegmentDataManagerList = getPrunedQueryableSegments(instanceRequest);
      segmentPruneTimer.stopAndRecord();

      queryRequest.setSegmentCountAfterPruning(queryableSegmentDataManagerList.size());
      LOGGER.debug("Matched {} segments", queryRequest.getSegmentCountAfterPruning());
      if (queryableSegmentDataManagerList.isEmpty()) {
        return new DataTableImplV2();
      }

      TimerContext.Timer planBuildTimer = timerContext.startNewPhaseTimer(ServerQueryPhase.BUILD_QUERY_PLAN);
      final Plan globalQueryPlan = _planMaker.makeInterSegmentPlan(queryableSegmentDataManagerList, brokerRequest,
          executorService, getResourceTimeOut(instanceRequest.getQuery()));
      planBuildTimer.stopAndRecord();

      if (_printQueryPlan) {
        LOGGER.debug("***************************** Query Plan for Request {} ***********************************",
            instanceRequest.getRequestId());
        globalQueryPlan.print();
        LOGGER.debug("*********************************** End Query Plan ***********************************");
      }

      TimerContext.Timer planExecTimer = timerContext.startNewPhaseTimer(ServerQueryPhase.QUERY_PLAN_EXECUTION);
      globalQueryPlan.execute();
      planExecTimer.stopAndRecord();

      dataTable = globalQueryPlan.getInstanceResponse();
      Map<String, String> dataTableMetadata = dataTable.getMetadata();
      queryProcessingTimer.stopAndRecord();

      LOGGER.debug("Searching Instance for Request Id - {}, browse took: {}", instanceRequest.getRequestId(),
          queryProcessingTimer.getDurationNs());
      LOGGER.debug("InstanceResponse for Request Id - {} : {}", instanceRequest.getRequestId(), dataTable.toString());
      dataTableMetadata
          .put(DataTable.TIME_USED_MS_METADATA_KEY, Long.toString(queryProcessingTimer.getDurationMs()));
      dataTableMetadata.put(DataTable.REQUEST_ID_METADATA_KEY, Long.toString(instanceRequest.getRequestId()));
      dataTableMetadata
          .put(DataTable.TRACE_INFO_METADATA_KEY, TraceContext.getTraceInfoOfRequestId(instanceRequest.getRequestId()));

      // Update the total docs in the metadata based on un-pruned segments.
      dataTableMetadata.put(DataTable.TOTAL_DOCS_METADATA_KEY, String.valueOf(_totalRawDocs));
      return dataTable;
    } catch (Exception e) {
      _serverMetrics.addMeteredQueryValue(instanceRequest.getQuery(), ServerMeter.QUERY_EXECUTION_EXCEPTIONS, 1);
      LOGGER.error("Exception processing requestId {}", requestId, e);
      dataTable = new DataTableImplV2();
      Map<String, String> dataTableMetadata = dataTable.getMetadata();
      dataTable.addException(QueryException.getException(QueryException.QUERY_EXECUTION_ERROR, e));
      TraceContext.logException("ServerQueryExecutorV1Impl", "Exception occurs in processQuery");
      queryProcessingTimer.stopAndRecord();

      LOGGER.info("Searching Instance for Request Id - {}, browse took: {}, instanceResponse: {}", requestId,
          queryProcessingTimer.getDurationMs(), dataTable.toString());
      dataTableMetadata
          .put(DataTable.TIME_USED_MS_METADATA_KEY, Long.toString(queryProcessingTimer.getDurationNs()));
      dataTableMetadata.put(DataTable.REQUEST_ID_METADATA_KEY, Long.toString(instanceRequest.getRequestId()));
      dataTableMetadata
          .put(DataTable.TRACE_INFO_METADATA_KEY, TraceContext.getTraceInfoOfRequestId(instanceRequest.getRequestId()));
      return dataTable;
    } finally {
      TableDataManager tableDataManager = _instanceDataManager.getTableDataManager(queryRequest.getTableName());
      if (tableDataManager != null && queryableSegmentDataManagerList != null) {
          for (SegmentDataManager segmentDataManager : queryableSegmentDataManagerList) {
            tableDataManager.releaseSegment(segmentDataManager);
          }
      }
      TraceContext.unregister(instanceRequest);
    }
  }

  private List<SegmentDataManager> getPrunedQueryableSegments(final InstanceRequest instanceRequest) {
    LOGGER.debug("InstanceRequest contains {} segments", instanceRequest.getSearchSegments().size());

    final String tableName = instanceRequest.getQuery().getQuerySource().getTableName();
    final TableDataManager tableDataManager = _instanceDataManager.getTableDataManager(tableName);
    if (tableDataManager == null || instanceRequest.getSearchSegmentsSize() == 0) {
      return new ArrayList<SegmentDataManager>();
    }
    List<SegmentDataManager> listOfQueryableSegments = tableDataManager.acquireSegments(
        instanceRequest.getSearchSegments());
    LOGGER.debug("TableDataManager found {} segments before pruning", listOfQueryableSegments.size());

    _totalRawDocs = 0; // Need to reset the total raw docs per query.
    Iterator<SegmentDataManager> it = listOfQueryableSegments.iterator();
    while (it.hasNext()) {
      SegmentDataManager segmentDataManager = it.next();
      final IndexSegment indexSegment = segmentDataManager.getSegment();
      // We need to compute the total raw docs for the table before any pruning.
      _totalRawDocs += indexSegment.getSegmentMetadata().getTotalRawDocs();
      if (_segmentPrunerService.prune(indexSegment, instanceRequest.getQuery())) {
        it.remove();
        tableDataManager.releaseSegment(segmentDataManager);
      }
    }
    return listOfQueryableSegments;
  }

  @Override
  public synchronized void shutDown() {
    if (isStarted()) {
      _isStarted = false;
      LOGGER.info("QueryExecutor is shutDown!");
    } else {
      LOGGER.warn("QueryExecutor is already shutDown, won't do anything!");
    }
  }

  @Override
  public boolean isStarted() {
    return _isStarted;
  }

  @Override
  public synchronized void start() {
    _isStarted = true;
    LOGGER.info("QueryExecutor is started!");
  }

  @Override
  public void updateResourceTimeOutInMs(String resource, long timeOutMs) {
    _resourceTimeOutMsMap.put(resource, timeOutMs);
  }

  private long getResourceTimeOut(BrokerRequest brokerRequest) {
    try {
      String resourceName = brokerRequest.getQuerySource().getTableName();
      if (_resourceTimeOutMsMap.containsKey(resourceName)) {
        return _resourceTimeOutMsMap.get(resourceName);
      }
    } catch (Exception e) {
      // Return the default timeout value
      LOGGER.warn("Caught exception while obtaining resource timeout", e);
    }
    return _defaultTimeOutMs;
  }
}
