// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.impala.catalog.MetaStoreClientPool;
import org.apache.impala.catalog.MetaStoreClientPool.MetaStoreClient;
import org.apache.impala.common.TransactionException;
import org.apache.impala.compat.MetastoreShim;
import org.apache.impala.thrift.TQueryCtx;
import org.apache.log4j.Logger;

import com.google.common.base.Preconditions;
import com.sun.tools.javac.code.Attribute.Array;

/**
 * Object of this class creates a daemon thread that periodically heartbeats the
 * registered transactions and locks to HMS to keep them alive.
 * TODO(IMPALA-8788) once we start opening a transaction for every query we should
 * re-think our wait policy to spread out RPCs in time.
 */
public class TransactionKeepalive {
  public static final Logger LOG = Logger.getLogger(TransactionKeepalive.class);

  // TODO: should be calculated from hive.txn.timeout or metastore.txn.timeout
  private static final long SLEEP_INTERVAL_SECONDS = 60;
  private static final long MILLION = 1000000L;
  private static final long BILLION = 1000000000L;

  final private Thread daemonThread_;

  private final MetaStoreClientPool metaStoreClientPool_;

  public static class HeartbeatContext {
    public TQueryCtx queryCtx;
    public long creationTime;

    public HeartbeatContext(TQueryCtx queryCtx, long creationTime) {
      this.queryCtx = queryCtx;
      this.creationTime = creationTime;
    }
  }

  // Map of transactions
  private Map<Long, HeartbeatContext> transactions_ = new HashMap<>();

  // Maps of locks.
  private Map<Long, HeartbeatContext> locks_ = new HashMap<>();

  private class DaemonThread implements Runnable {
    /**
     * Background thread does the periodic heartbeating.
     */
    @Override
    public void run() {
      Random rand = new Random();
      try {
        // Let's sleep for a random interval to make the different coordinators
        // out-of-sync to each other. This way we probably lower the workload on HMS.
        Thread.sleep(rand.nextInt((int)(SLEEP_INTERVAL_SECONDS * 1000)));
      } catch (Throwable e) {
        LOG.error("Unexpected exception thrown", e);
      }
      while (true) {
        try {
          // Let's deepcopy the transactions and locks to narrow the critical section.
          Map<Long, HeartbeatContext> copyOfTransactions;
          Map<Long, HeartbeatContext> copyOfLocks;
          synchronized (TransactionKeepalive.this) {
            copyOfTransactions = transactions_.entrySet().stream().collect(
                Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
            copyOfLocks = locks_.entrySet().stream().collect(
                Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
          }
          long durationOfHeartbeatingMillis = 0;
          if (!copyOfTransactions.isEmpty() || !copyOfLocks.isEmpty()) {
            LOG.info("There are " + String.valueOf(copyOfTransactions.size()) +
                " open transactions and " + String.valueOf(copyOfLocks) +
                " independent locks in TransactionKeepalive. Start heartbeating them.");
            long startHeartbeating = System.nanoTime();
            sendHeartbeatsFor(copyOfTransactions, copyOfLocks);
            durationOfHeartbeatingMillis =
                (System.nanoTime() - startHeartbeating) / MILLION;
            LOG.info("Heartbeating the transactions and locks took " +
                durationOfHeartbeatingMillis + " milliseconds.");
          }
          long sleepMillis = SLEEP_INTERVAL_SECONDS * 1000 - durationOfHeartbeatingMillis;
          if (sleepMillis > 0) {
            long randomness = rand.nextInt((int)(sleepMillis / 10));
            Thread.sleep(sleepMillis + randomness);
          }
        } catch (Throwable e) {
          LOG.error("Unexpected exception thrown", e);
        }
      }
    }

    /**
     * Sends heartbeats for transactions and locks that are old enough, i.e. older than
     * the sleep interval.
     * TODO: we can be more clever than that and should also take into consideration
     * metastore.txn.timeout as well.
     */
    private void sendHeartbeatsFor(Map<Long, HeartbeatContext> transactions,
        Map<Long, HeartbeatContext> locks) {
      try (MetaStoreClient client = metaStoreClientPool_.getClient()) {
        IMetaStoreClient hmsClient = client.getHiveClient();
        for (Map.Entry<Long, HeartbeatContext> entry : transactions.entrySet()) {
          HeartbeatContext ctx = entry.getValue();
          // Only heartbeat old transactions
          if (oldEnough(ctx)) {
            Long transactionId = entry.getKey();
            sendHeartbeat(hmsClient, transactionId, 0L, ctx);
          }
        }
        for (Map.Entry<Long, HeartbeatContext> entry : locks.entrySet()) {
          HeartbeatContext ctx = entry.getValue();
          // Only heartbeat old locks
          if (oldEnough(ctx)) {
            Long lockId = entry.getKey();
            sendHeartbeat(hmsClient, 0L, lockId, ctx);
          }
        }
      }
    }

    /**
     * Determines whether a transaction or lock is old enough for heartbeating.
     * @param heartbeatContext context information about creation time.
     * @return True if we should heartbeat this entry.
     */
    private boolean oldEnough(HeartbeatContext heartbeatContext) {
      Long ageInSeconds = (System.nanoTime() - heartbeatContext.creationTime) / BILLION;
      return ageInSeconds > SLEEP_INTERVAL_SECONDS;
    }

    /**
     * Sends a single heartbeat for 'transactionId' or 'lockId'.
     */
    private void sendHeartbeat(IMetaStoreClient hmsClient, long transactionId,
        long lockId, HeartbeatContext context) {
      // One of the values must be zero, but only one.
      Preconditions.checkState(transactionId == 0 || lockId == 0);
      Preconditions.checkState(transactionId != 0 || lockId != 0);
      try {
        if (!MetastoreShim.heartbeat(hmsClient, transactionId, lockId)) {
          // Transaction or lock doesn't exist anymore, let's remove them.
          if (transactionId != 0) {
            LOG.warn("Transaction " + String.valueOf(transactionId) + " of query " +
                context.queryCtx.query_id.toString() + " doesn't exist anymore. Stop " +
                "heartbeating it.");
            TransactionKeepalive.this.deleteTransaction(transactionId);
          }
          if (lockId != 0) {
            LOG.warn("Lock " + String.valueOf(lockId) + " of query " +
                context.queryCtx.query_id.toString() + " doesn't exist anymore. Stop " +
                "heartbeating it.");
            TransactionKeepalive.this.deleteLock(lockId);
          }
        }
      } catch (TransactionException e) {
        LOG.warn("Caught exception during heartbeating transaction " +
            String.valueOf(transactionId) + " lock " + String.valueOf(lockId) +
            " for query " + context.queryCtx.query_id.toString(), e);
      }
    }
  }

  /**
   * Creates TransactionKeepalive object and starts the background thread.
   */
  public TransactionKeepalive(MetaStoreClientPool metaStoreClientPool) {
    Preconditions.checkNotNull(metaStoreClientPool);
    metaStoreClientPool_ = metaStoreClientPool;
    daemonThread_ = new Thread(new DaemonThread());
    daemonThread_.setDaemon(true);
    daemonThread_.setName("Transaction keepalive thread");
    daemonThread_.start();
  }

  /**
   * Add transaction to heartbeat. Associated locks shouldn't be added.
   */
  synchronized public void addTransaction(Long transactionId, TQueryCtx queryCtx) {
    Preconditions.checkNotNull(transactionId);
    Preconditions.checkNotNull(queryCtx);
    Preconditions.checkState(!transactions_.containsKey(transactionId));
    HeartbeatContext ctx = new HeartbeatContext(queryCtx, System.nanoTime());
    transactions_.put(transactionId, ctx);
  }

  /**
   * Add lock to heartbeat. This should be a lock without a transaction context.
   */
  synchronized public void addLock(Long lockId, TQueryCtx queryCtx) {
    Preconditions.checkNotNull(lockId);
    Preconditions.checkNotNull(queryCtx);
    Preconditions.checkState(!locks_.containsKey(lockId));
    HeartbeatContext ctx = new HeartbeatContext(queryCtx, System.nanoTime());
    locks_.put(lockId, ctx);
  }

  /**
   * Stop heartbeating transaction.
   */
  synchronized public void deleteTransaction(Long transactionId) {
    Preconditions.checkNotNull(transactionId);
    if (transactions_.remove(transactionId) == null) {
      LOG.info("Transaction id " + transactionId + " was already removed from " +
          "TransactionKeepalive object or never existed.");
    };
  }

  /**
   * Stop heartbeating lock.
   */
  synchronized public void deleteLock(Long lockId) {
    Preconditions.checkNotNull(lockId);
    if (locks_.remove(lockId) == null) {
      LOG.info("Lock id " + lockId + " was already removed from " +
          "TransactionKeepalive object or never existed.");
    };
  }
}
