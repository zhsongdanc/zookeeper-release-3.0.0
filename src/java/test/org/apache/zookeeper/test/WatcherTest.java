/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;
import org.apache.zookeeper.ClientCnxn;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.junit.Test;

public class WatcherTest extends ClientBase {
    protected static final Logger LOG = Logger.getLogger(WatcherTest.class);

    private class MyWatcher extends CountdownWatcher {
        LinkedBlockingQueue<WatchedEvent> events =
            new LinkedBlockingQueue<WatchedEvent>();

        public void process(WatchedEvent event) {
            super.process(event);
            if (event.getType() != Event.EventType.None) {
                try {
                    events.put(event);
                } catch (InterruptedException e) {
                    LOG.warn("ignoring interrupt during event.put");
                }
            }
        }
    }

    /**
     * Verify that we get all of the events we expect to get. This particular
     * case verifies that we see all of the data events on a particular node.
     * There was a bug (ZOOKEEPER-137) that resulted in events being dropped
     * in some cases (timing).
     * 
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    @Test
    public void testWatcherCorrectness()
        throws IOException, InterruptedException, KeeperException
    {
        ZooKeeper zk = null;
        try {
            MyWatcher watcher = new MyWatcher();
            zk = createClient(watcher, hostPort);
            
            StatCallback scb = new StatCallback() {
                public void processResult(int rc, String path, Object ctx,
                        Stat stat) {
                    // don't do anything
                }
            };
            VoidCallback vcb = new VoidCallback() {
                public void processResult(int rc, String path, Object ctx) {
                    // don't do anything
                }
            };
            
            String names[] = new String[10];
            for (int i = 0; i < names.length; i++) {
                String name = zk.create("/tc-", "initialvalue".getBytes(),
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                names[i] = name;
    
                Stat stat = new Stat();
                zk.getData(name, watcher, stat);
                zk.setData(name, "new".getBytes(), stat.getVersion(), scb, null);
                stat = zk.exists(name, watcher);
                zk.delete(name, stat.getVersion(), vcb, null);
            }
            
            for (int i = 0; i < names.length; i++) {
                String name = names[i];
                WatchedEvent event = watcher.events.poll(10, TimeUnit.SECONDS);
                assertEquals(name, event.getPath());
                assertEquals(Event.EventType.NodeDataChanged, event.getType());
                assertEquals(Event.KeeperState.SyncConnected, event.getState());
                event = watcher.events.poll(10, TimeUnit.SECONDS);
                assertEquals(name, event.getPath());
                assertEquals(Event.EventType.NodeDeleted, event.getType());
                assertEquals(Event.KeeperState.SyncConnected, event.getState());
            }
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    @Test
    public void testWatcherAutoResetWithGlobal() throws Exception {
        ZooKeeper zk = null;
        MyWatcher watcher = new MyWatcher();
        zk = createClient(watcher, hostPort);
        testWatcherAutoReset(zk, watcher, watcher);
        zk.close();
    }
        
    @Test
    public void testWatcherAutoResetWithLocal() throws Exception {
        ZooKeeper zk = null;
        MyWatcher watcher = new MyWatcher();
        zk = createClient(watcher, hostPort);
        testWatcherAutoReset(zk, watcher, new MyWatcher());
        zk.close();
    }
        
    @Test
    public void testWatcherAutoResetDisabledWithGlobal() throws Exception {
        ClientCnxn.disableAutoWatchReset = true;
        testWatcherAutoResetWithGlobal();
    }
        
    @Test
    public void testWatcherAutoResetDisabledWithLocal() throws Exception {
        ClientCnxn.disableAutoWatchReset = true;
        testWatcherAutoResetWithLocal();
    }
        
    private void testWatcherAutoReset(ZooKeeper zk, MyWatcher globalWatcher, 
            MyWatcher localWatcher) throws Exception {
        boolean isGlobal = (localWatcher == globalWatcher);
        // First test to see if the watch survives across reconnects
        zk.create("/watchtest", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/watchtest/child", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        if (isGlobal) {
            zk.getChildren("/watchtest", true);
            zk.getData("/watchtest/child", true, new Stat());
            zk.exists("/watchtest/child2", true);
        } else {
            zk.getChildren("/watchtest", localWatcher);
            zk.getData("/watchtest/child", localWatcher, new Stat());
            zk.exists("/watchtest/child2", localWatcher);
        }
        
        assertTrue(localWatcher.events.isEmpty());
        
        stopServer();
        globalWatcher.waitForDisconnected(3000);
        localWatcher.waitForDisconnected(500);
        startServer();
        globalWatcher.waitForConnected(3000);
        if (!isGlobal && !ClientCnxn.disableAutoWatchReset) {
            localWatcher.waitForConnected(500);
        }
        
        assertTrue(localWatcher.events.isEmpty());
        zk.setData("/watchtest/child", new byte[1], -1);
        zk.create("/watchtest/child2", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        
        WatchedEvent e = localWatcher.events.poll(1, TimeUnit.MILLISECONDS);
        if (!ClientCnxn.disableAutoWatchReset) {
            assertEquals(e.getPath(), EventType.NodeDataChanged, e.getType());
            assertEquals("/watchtest/child", e.getPath());
        } else {
            assertNull("unexpected event", e);
        }
    
        e = localWatcher.events.poll(1000, TimeUnit.MILLISECONDS);
        if (!ClientCnxn.disableAutoWatchReset) {
            // The create will trigger the get children and the exist
            // watches
            assertEquals(EventType.NodeCreated, e.getType());
            assertEquals("/watchtest/child2", e.getPath());
        } else {
            assertNull("unexpected event", e);
        }

        e = localWatcher.events.poll(1000, TimeUnit.MILLISECONDS);
        if (!ClientCnxn.disableAutoWatchReset) {
            assertEquals(EventType.NodeChildrenChanged, e.getType());
            assertEquals("/watchtest", e.getPath());
        } else {
            assertNull("unexpected event", e);
        }
        
        // Make sure PINGs don't screw us up!
        Thread.sleep(4000);
        
        assertTrue(localWatcher.events.isEmpty()); // ensure no late arrivals
        stopServer();
        globalWatcher.waitForDisconnected(3000);
        try {
        try {
            localWatcher.waitForDisconnected(500);
            if (!isGlobal && !ClientCnxn.disableAutoWatchReset) {
                fail("Got an event when I shouldn't have");
            }
        } catch(TimeoutException toe) {
            if (ClientCnxn.disableAutoWatchReset) {
                fail("Didn't get an event when I should have");
            }
            // Else what we are expecting since there are no outstanding watches
        }
        } catch (Exception e1) {
            LOG.error("bad", e1);
            throw new RuntimeException(e1);
        }
        startServer();
        globalWatcher.waitForConnected(3000);
        
        if (isGlobal) {
            zk.getChildren("/watchtest", true);
            zk.getData("/watchtest/child", true, new Stat());
            zk.exists("/watchtest/child2", true);
        } else {
            zk.getChildren("/watchtest", localWatcher);
            zk.getData("/watchtest/child", localWatcher, new Stat());
            zk.exists("/watchtest/child2", localWatcher);
        }
        
        // Do trigger an event to make sure that we do not get
        // it later
        zk.delete("/watchtest/child2", -1);
        
        e = localWatcher.events.poll(1, TimeUnit.MILLISECONDS);
        assertEquals(EventType.NodeDeleted, e.getType());
        assertEquals("/watchtest/child2", e.getPath());
        
        e = localWatcher.events.poll(1, TimeUnit.MILLISECONDS);
        assertEquals(EventType.NodeChildrenChanged, e.getType());
        assertEquals("/watchtest", e.getPath());
        
        assertTrue(localWatcher.events.isEmpty());
        
        stopServer();
        globalWatcher.waitForDisconnected(3000);
        localWatcher.waitForDisconnected(500);
        startServer();
        globalWatcher.waitForConnected(3000);
        if (!isGlobal && !ClientCnxn.disableAutoWatchReset) {
            localWatcher.waitForConnected(500);
        }
        
        zk.delete("/watchtest/child", -1);
        zk.delete("/watchtest", -1);
        
        e = localWatcher.events.poll(1, TimeUnit.MILLISECONDS);
        if (!ClientCnxn.disableAutoWatchReset) {
            assertEquals(EventType.NodeDeleted, e.getType());
            assertEquals("/watchtest/child", e.getPath());
        } else {
            assertNull("unexpected event", e);
        }
        
        // Make sure nothing is straggling!
        Thread.sleep(1000);
        assertTrue(localWatcher.events.isEmpty());
        
    }

}
