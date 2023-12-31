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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.upgrade.UpgradeMain;

public class UpgradeTest extends TestCase implements Watcher {
    private final static Logger LOG = Logger.getLogger(UpgradeTest.class);
    private static String HOSTPORT = "127.0.0.1:2359";
    ZooKeeperServer zks;
    private static final File testData = new File(
            System.getProperty("test.data.dir", "build/test/data"));
    private CountDownLatch startSignal;
    
    @Override
    protected void setUp() throws Exception {
        LOG.info("STARTING " + getName());
        ServerStats.registerAsConcrete();
    }
    @Override
    protected void tearDown() throws Exception {
        ServerStats.unregister();
        LOG.info("FINISHED " + getName());
    }
    
    /**
     * test the upgrade
     * @throws Exception
     */
    public void testUpgrade() throws Exception {
        File upgradeDir = new File(testData, "upgrade");
        UpgradeMain upgrade = new UpgradeMain(upgradeDir, upgradeDir);
        upgrade.runUpgrade();
        zks = new ZooKeeperServer(upgradeDir, upgradeDir, 3000);
        SyncRequestProcessor.snapCount = 1000;
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(PORT);
        f.startup(zks);
        LOG.info("starting up the zookeeper server .. waiting");
        assertTrue("waiting for server being up", 
                ClientBase.waitForServerUp(HOSTPORT,CONNECTION_TIMEOUT));
        ZooKeeper zk = new ZooKeeper(HOSTPORT, 20000, this);
        Stat stat = zk.exists("/", false);
        List<String> children = zk.getChildren("/", false);
        Collections.sort(children);
        for (int i=0; i < 10; i++) {
            assertTrue("data tree sanity check",
                    ("test-"+ i).equals(children.get(i)));
        }
        //try creating one node
        zk.create("/upgrade","upgrade".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        // check if its there
        if (zk.exists("/upgrade",false) == null) {
            assertTrue(false);
        }
        // bring down the server
        f.shutdown();
        assertTrue("waiting for server down",
                   ClientBase.waitForServerDown(HOSTPORT,
                           ClientBase.CONNECTION_TIMEOUT));
        
    }
    
    public void process(WatchedEvent event) {
        LOG.info("Event:" + event.getState() + " " + event.getType() + " " + event.getPath());
        if (event.getState() == KeeperState.SyncConnected
                && startSignal != null && startSignal.getCount() > 0)
        {              
            startSignal.countDown();      
        }
    }
}