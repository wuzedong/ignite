/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.datastructures.partitioned;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.datastructures.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.junits.common.*;
import org.apache.ignite.transactions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static java.util.concurrent.TimeUnit.*;
import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CachePreloadMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;
import static org.apache.ignite.transactions.IgniteTxConcurrency.*;
import static org.apache.ignite.transactions.IgniteTxIsolation.*;

/**
 *
 */
public class GridCachePartitionedQueueCreateMultiNodeSelfTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        TcpDiscoverySpi spi = new TcpDiscoverySpi();

        spi.setIpFinder(ipFinder);

        c.setDiscoverySpi(spi);
        c.setIncludeEventTypes();
        c.setPeerClassLoadingEnabled(false);

        c.setCacheConfiguration(cacheConfiguration());

        return c;
    }

    /** {@inheritDoc} */
    protected CacheConfiguration cacheConfiguration() {
        CacheConfiguration cc = defaultCacheConfiguration();

        cc.setCacheMode(PARTITIONED);
        cc.setWriteSynchronizationMode(FULL_SYNC);
        cc.setPreloadMode(SYNC);
        cc.setBackups(0);

        return cc;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids(true);
    }

    /**
     * @throws Exception If failed.
     */
    public void testQueueCreation() throws Exception {
        final AtomicInteger idx = new AtomicInteger();

        IgniteInternalFuture<?> fut = multithreadedAsync(
            new Callable<Object>() {
                @Override public Object call() throws Exception {
                    Ignite ignite = startGrid(idx.getAndIncrement());

                    UUID locNodeId = ignite.cluster().localNode().id();

                    info("Started grid: " + locNodeId);

                    GridCache<String, ?> cache = ignite.cache(null);

                    info("Creating queue: " + locNodeId);

                    CacheQueue<String> q = cache.dataStructures().queue("queue", 1, true, true);

                    assert q != null;

                    info("Putting first value: " + locNodeId);

                    q.offer("val", 1000, MILLISECONDS);

                    info("Putting second value: " + locNodeId);

                    boolean res2 = q.offer("val1", 1000, MILLISECONDS);

                    assert !res2;

                    info("Thread finished: " + locNodeId);

                    return null;
                }
            },
            10
        );

        fut.get();
    }

    /**
     * @throws Exception If failed.
     */
    public void testTx() throws Exception {
        if (cacheConfiguration().getAtomicityMode() != TRANSACTIONAL)
            return;

        int threadCnt = 10;

        final AtomicInteger idx = new AtomicInteger();
        final AtomicBoolean flag = new AtomicBoolean();

        final CountDownLatch latch = new CountDownLatch(threadCnt);

        IgniteInternalFuture<?> fut = multithreadedAsync(
            new Callable<Object>() {
                @Override public Object call() throws Exception {
                    Ignite ignite = startGrid(idx.getAndIncrement());

                    boolean wait = false;

                    if (wait) {
                        latch.countDown();

                        latch.await();
                    }

                    // If output presents, test passes with greater probability.
                    // info("Start puts.");

                    GridCache<Integer, String> cache = ignite.cache(null);

                    info("Partition: " + cache.affinity().partition(1));

                    try (IgniteTx tx = cache.txStart(PESSIMISTIC, REPEATABLE_READ)) {
                        // info("Getting value for key 1");

                        String s = cache.get(1);

                        // info("Got value: " + s);

                        if (s == null) {
                            assert flag.compareAndSet(false, true);

                            // info("Putting value.");

                            cache.putx(1, "val");

                            // info("Done putting value");

                            tx.commit();
                        }
                        else
                            assert "val".equals(s) : "String: " + s;
                    }

                    info("Thread finished for grid: " + ignite.name());

                    return null;
                }
            },
            threadCnt
        );

        fut.get();
    }
}
