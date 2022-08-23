/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.sink.compact;

import org.apache.hudi.avro.model.HoodieCompactionPlan;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.data.HoodieListData;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.configuration.FlinkOptions;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.sink.CleanFunction;
import org.apache.hudi.table.HoodieFlinkTable;
import org.apache.hudi.table.action.compact.CompactHelpers;
import org.apache.hudi.util.CompactionUtil;
import org.apache.hudi.util.StreamerUtil;

import org.apache.flink.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Function to check and commit the compaction action.
 *
 * <p> Each time after receiving a compaction commit event {@link CompactionCommitEvent},
 * it loads and checks the compaction plan {@link HoodieCompactionPlan},
 * if all the compaction operations {@link org.apache.hudi.common.model.CompactionOperation}
 * of the plan are finished, tries to commit the compaction action.
 *
 * <p>It also inherits the {@link CleanFunction} cleaning ability. This is needed because
 * the SQL API does not allow multiple sinks in one table sink provider.
 */
public class CompactionCommitSink extends CleanFunction<CompactionCommitEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(CompactionCommitSink.class);

  /**
   * Config options.
   */
  private final Configuration conf;

  /**
   * Buffer to collect the event from each compact task {@code CompactFunction}.
   *
   * <p>Stores the mapping of instant_time -> file_id -> event. Use a map to collect the
   * events because the rolling back of intermediate compaction tasks generates corrupt
   * events.
   */
  private transient Map<String, Map<String, CompactionCommitEvent>> commitBuffer;

  /**
   * Caches the no ready compaction plans instants
   */
  private Set<String> noReadyCache;

  /**
   * The hoodie table.
   */
  private transient HoodieFlinkTable<?> table;

  /**
   * The compaction plans
   */
  private List<Pair<String, HoodieCompactionPlan>> compactionPlans;

  /**
   * the pos of the compaction plan next to commit
   */
  private transient int nextToCommit;

  public CompactionCommitSink(Configuration conf, List<Pair<String, HoodieCompactionPlan>> compactionPlans) {
    super(conf);
    this.conf = conf;
    this.compactionPlans = compactionPlans;
    this.noReadyCache = new HashSet<>();
    this.commitBuffer = new HashMap<>();
    for (Pair<String, HoodieCompactionPlan> pair : compactionPlans) {
      noReadyCache.add(pair.getLeft());
    }
  }

  @Override
  public void open(Configuration parameters) throws Exception {
    super.open(parameters);
    if (writeClient == null) {
      this.writeClient = StreamerUtil.createWriteClient(conf, getRuntimeContext());
    }
    if (this.commitBuffer == null) {
      this.commitBuffer = new HashMap<>();
    }
    this.table = this.writeClient.getHoodieTable();
    this.nextToCommit = 0;
  }

  @Override
  public void invoke(CompactionCommitEvent event, Context context) throws Exception {
    final String instant = event.getInstant();
    commitBuffer.computeIfAbsent(instant, k -> new HashMap<>())
        .put(event.getFileId(), event);
    commitIfNecessary(instant, commitBuffer.get(instant).values());
  }

  /**
   * Condition to commit: the commit buffer has equal size with the compaction plan operations
   * and all the compact commit event {@link CompactionCommitEvent} has the same compaction instant time.
   *
   * @param instant Compaction commit instant time
   * @param events  Commit events ever received for the instant
   */
  private void commitIfNecessary(String instant, Collection<CompactionCommitEvent> events) throws IOException {
    HoodieCompactionPlan compactionPlan = null;
    for (Pair<String, HoodieCompactionPlan> pair : compactionPlans) {
      if (instant.equals(pair.getLeft())) {
        compactionPlan = pair.getRight();
        break;
      }
    }
    if (compactionPlan == null) {
      throw new HoodieException();
    }
    boolean isReady = compactionPlan.getOperations().size() == events.size();
    if (!isReady) {
      return;
    }
    if (events.stream().anyMatch(CompactionCommitEvent::isFailed)) {
      try {
        // handle failure case
        CompactionUtil.rollbackCompaction(table, instant);
      } finally {
        // remove commitBuffer to avoid obsolete metadata commit
        reset(instant);
      }
      return;
    }
    // remove the instant that is ready to commit
    noReadyCache.remove(instant);

    //commit all the ready instant in order of the compaction plan
    while (isOrderToCommit(nextToCommit, noReadyCache, compactionPlans)) {
      String nextInstant = compactionPlans.get(nextToCommit).getLeft();
      Collection<CompactionCommitEvent> nextEvents = commitBuffer.get(instant).values();
      try {
        doCommit(nextInstant, nextEvents);
      } catch (Throwable throwable) {
        // make it fail-safe
        LOG.error("Error while committing compaction instant: " + nextInstant, throwable);
      } finally {
        // reset the status
        reset(nextInstant);
      }
      ++nextToCommit;
    }
  }

  @SuppressWarnings("unchecked")
  private void doCommit(String instant, Collection<CompactionCommitEvent> events) throws IOException {
    List<WriteStatus> statuses = events.stream()
        .map(CompactionCommitEvent::getWriteStatuses)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());

    HoodieCommitMetadata metadata = CompactHelpers.getInstance().createCompactionMetadata(
        table, instant, HoodieListData.eager(statuses), writeClient.getConfig().getSchema());

    // commit the compaction
    this.writeClient.commitCompaction(instant, metadata, Option.empty());

    // Whether to clean up the old log file when compaction
    if (!conf.getBoolean(FlinkOptions.CLEAN_ASYNC_ENABLED)) {
      this.writeClient.clean();
    }
  }

  private void reset(String instant) {
    this.commitBuffer.remove(instant);
  }

  private boolean isOrderToCommit(int nextToCommit, Set<String> noReadyCache, List<Pair<String, HoodieCompactionPlan>> compactionPlans) {
    return nextToCommit < compactionPlans.size() && !noReadyCache.contains(compactionPlans.get(nextToCommit).getLeft());
  }
}
