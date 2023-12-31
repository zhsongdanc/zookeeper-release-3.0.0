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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.ACLCallback;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

/**
 * The intent of these classes is to support testing the functionality of
 * asynchronous client operations. Both positive as well as negative tests.
 * 
 * This code acts as a "contract checker" of sorts. We look at the 
 * actual output as well as the expected output - if the actual output 
 * changes over time this code should catch the regression and alert to a 
 * potentially unwanted (unexpected) change.
 * 
 * In addition these classes can be re-used by other tests that need to
 * perform these operations. In general the classes err on the side of
 * convention over a lot of setup - such that you can use start using them
 * w/o a lot of though (default path/data/acl/etc...). See AsyncOpsTest
 * for some good examples of use.
 */
public class AsyncOps {
    /**
     * This is the base class for all of the async callback classes. It will
     * verify the expected value against the actual value.
     * 
     * Basic operation is that the subclasses will generate an "expected" value
     * which is defined by the "toString" method of the subclass. This is
     * passed through to the verify clause by specifying it as the ctx object
     * of each async call (processResult methods get the ctx as part of
     * the callback). Additionally the callback will also overwrite any
     * instance fields with matching parameter arguments to the processResult
     * method. The cb instance can then compare the expected to the
     * actual value by again calling toString and comparing the two.
     * 
     * The format of each expected value differs (is defined) by subclass.
     * Generally the expected value starts with the result code (rc) and path
     * of the node being operated on, followed by the fields specific to
     * each operation type (cb subclass). For example ChildrenCB specifies
     * a list of the expected children suffixed onto the rc and path. See
     * the toString() method of each subclass for details of it's format. 
     */
    public static abstract class AsyncCB {
        protected final ZooKeeper zk;
        protected long defaultTimeoutMillis = 30000;
        
        /** the latch is used to await the results from the server */
        CountDownLatch latch;

        int rc = 0;
        String path = "/foo";
        String expected;
        
        public AsyncCB(ZooKeeper zk, CountDownLatch latch) {
            this.zk = zk;
            this.latch = latch;
        }
        
        public void setRC(int rc) {
            this.rc = rc;
        }
        
        public void setPath(String path) {
            this.path = path;
        }
        
        public void processResult(int rc, String path, Object ctx)
        {
            this.rc = rc;
            this.path = path;
            this.expected = (String)ctx;
            latch.countDown();
        }
        
        /** String format is rc:path:<suffix> where <suffix> is defined by each
         * subclass individually. */
        @Override
        public String toString() {
            return rc + ":" + path + ":"; 
        }

        protected void verify() {
            try {
                latch.await(defaultTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                fail("unexpected interrupt");
            }
            // on the lookout for timeout
            assertSame(0L, latch.getCount());
            
            String actual = toString();
            
            assertEquals(expected, actual);
        }
    }
    
    public static class StringCB extends AsyncCB implements StringCallback {
        byte[] data = new byte[10];
        List<ACL> acl = Ids.CREATOR_ALL_ACL;
        CreateMode flags = CreateMode.PERSISTENT;
        String name = path;
        
        StringCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }
        
        StringCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
        }
        
        public void setPath(String path) {
            super.setPath(path);
            this.name = path;
        }
        
        public String nodeName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
        
        public void processResult(int rc, String path, Object ctx, String name)
        {
            this.name = name;
            super.processResult(rc, path, ctx);
        }

        public AsyncCB create() {
            zk.create(path, data, acl, flags, this, toString());
            return this;
        }
        
        public void verifyCreate() {
            create();
            verify();
        }
        
        public void verifyCreateFailure_NodeExists() {
            new StringCB(zk).verifyCreate();
            
            rc = Code.NodeExists;
            name = null;
            zk.create(path, data, acl, flags, this, toString());
            verify();
        }
        
        @Override
        public String toString() {
            return super.toString() + name; 
        }
    }

    public static class ACLCB extends AsyncCB implements ACLCallback {
        List<ACL> acl = Ids.CREATOR_ALL_ACL;
        int version = 0;
        Stat stat = new Stat();
        byte[] data = "testing".getBytes();
        
        ACLCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        ACLCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
            stat.setAversion(0);
            stat.setCversion(0);
            stat.setEphemeralOwner(0);
            stat.setVersion(0);
        }

        public void processResult(int rc, String path, Object ctx,
                List<ACL> acl, Stat stat)
        {
            this.acl = acl;
            this.stat = stat;
            super.processResult(rc, path, ctx);
        }
        
        public void verifyGetACL() {
            new StringCB(zk).verifyCreate();

            zk.getACL(path, stat, this, toString());
            verify();
        }
        
        public String toString(List<ACL> acls) {
            StringBuffer result = new StringBuffer();
            for(ACL acl : acls) {
                result.append(acl.getPerms() + "::");
            }
            return result.toString();
        }
        
        @Override
        public String toString() {
            return super.toString() + toString(acl) + ":" 
                + ":" + version + ":" + new String(data)
                + ":" + (stat == null ? "null" : stat.getAversion() + ":" 
                        + stat.getCversion() + ":" + stat.getEphemeralOwner()
                        + ":" + stat.getVersion()); 
        }
    }

    public static class ChildrenCB extends AsyncCB implements ChildrenCallback {
        List<String> children = new ArrayList<String>();
        
        ChildrenCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        ChildrenCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
        }
        
        public void processResult(int rc, String path, Object ctx,
                List<String> children)
        {
            this.children =
                (children == null ? new ArrayList<String>() : children);
            super.processResult(rc, path, ctx);
        }
        
        public StringCB createNode() {
            StringCB parent = new StringCB(zk);
            parent.verifyCreate();

            return parent;
        }
        
        public StringCB createNode(StringCB parent) {
            String childName = "bar";

            return createNode(parent, childName);
        }

        public StringCB createNode(StringCB parent, String childName) {
            StringCB child = new StringCB(zk);
            child.setPath(parent.path + "/" + childName);
            child.verifyCreate();
            
            return child;
        }
        
        public void verifyGetChildrenEmpty() {
            StringCB parent = createNode();

            path = parent.path;
            verify();
        }
        
        public void verifyGetChildrenSingle() {
            StringCB parent = createNode();
            StringCB child = createNode(parent);

            path = parent.path;
            children.add(child.nodeName());
            
            verify();
        }
        
        public void verifyGetChildrenTwo() {
            StringCB parent = createNode();
            StringCB child1 = createNode(parent, "child1");
            StringCB child2 = createNode(parent, "child2");

            path = parent.path;
            children.add(child1.nodeName());
            children.add(child2.nodeName());
            
            verify();
        }
        
        public void verifyGetChildrenFailure_NoNode() {
            rc = KeeperException.Code.NoNode;
            verify();
        }
        
        @Override
        public void verify() {
            zk.getChildren(path, false, this, toString());

            super.verify();
        }
        
        @Override
        public String toString() {
            return super.toString() + children.toString(); 
        }
    }

    public static class DataCB extends AsyncCB implements DataCallback {
        byte[] data = new byte[10];
        Stat stat = new Stat();
        
        DataCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        DataCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
            stat.setAversion(0);
            stat.setCversion(0);
            stat.setEphemeralOwner(0);
            stat.setVersion(0);
        }
        
        public void processResult(int rc, String path, Object ctx, byte[] data,
                Stat stat)
        {
            this.data = data;
            this.stat = stat;
            super.processResult(rc, path, ctx);
        }
        
        public void verifyGetData() {
            new StringCB(zk).verifyCreate();

            zk.getData(path, false, this, toString());
            verify();
        }
        
        public void verifyGetDataFailure_NoNode() {
            rc = KeeperException.Code.NoNode;
            data = null;
            stat = null;
            zk.getData(path, false, this, toString());
            verify();
        }
        
        @Override
        public String toString() {
            return super.toString()
                + ":" + (data == null ? "null" : new String(data))
                + ":" + (stat == null ? "null" : stat.getAversion() + ":" 
                    + stat.getCversion() + ":" + stat.getEphemeralOwner()
                    + ":" + stat.getVersion()); 
        }
    }

    public static class StatCB extends AsyncCB implements StatCallback {
        List<ACL> acl = Ids.CREATOR_ALL_ACL;
        int version = 0;
        Stat stat = new Stat();
        byte[] data = "testing".getBytes();
        
        StatCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }

        StatCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
            stat.setAversion(0);
            stat.setCversion(0);
            stat.setEphemeralOwner(0);
            stat.setVersion(0);
        }
        
        public void processResult(int rc, String path, Object ctx, Stat stat) {
            this.stat = stat;
            super.processResult(rc, path, ctx);
        }
        
        public void verifySetACL() {
            stat.setAversion(1);
            new StringCB(zk).verifyCreate();

            zk.setACL(path, acl, version, this, toString());
            verify();
        }
        
        public void verifySetACLFailure_NoNode() {
            rc = KeeperException.Code.NoNode;
            stat = null;
            zk.setACL(path, acl, version, this, toString());
            verify();
        }
        
        public void setData() {
            zk.setData(path, data, version, this, toString());
        }
        
        public void verifySetData() {
            stat.setVersion(1);
            new StringCB(zk).verifyCreate();

            setData();
            verify();
        }
        
        public void verifySetDataFailure_NoNode() {
            rc = KeeperException.Code.NoNode;
            stat = null;
            zk.setData(path, data, version, this, toString());
            verify();
        }
        
        public void verifyExists() {
            new StringCB(zk).verifyCreate();

            zk.exists(path, false, this, toString());
            verify();
        }
        
        public void verifyExistsFailure_NoNode() {
            rc = KeeperException.Code.NoNode;
            stat = null;
            zk.exists(path, false, this, toString());
            verify();
        }
        
        @Override
        public String toString() {
            return super.toString() + version
                + ":" + new String(data)
                + ":" + (stat == null ? "null" : stat.getAversion() + ":" 
                        + stat.getCversion() + ":" + stat.getEphemeralOwner()
                        + ":" + stat.getVersion()); 
        }
    }

    public static class VoidCB extends AsyncCB implements VoidCallback {
        int version = 0;
        
        VoidCB(ZooKeeper zk) {
            this(zk, new CountDownLatch(1));
        }
        
        VoidCB(ZooKeeper zk, CountDownLatch latch) {
            super(zk, latch);
        }
        
        public void delete() {
            zk.delete(path, version, this, toString());
        }
        
        public void verifyDelete() {
            new StringCB(zk).verifyCreate();

            delete();
            verify();
        }
        
        public void verifyDeleteFailure_NoNode() {
            rc = Code.NoNode;
            zk.delete(path, version, this, toString());
            verify();
        }
        
        public void sync() {
            zk.sync(path, this, toString());
        }
        
        public void verifySync() {
            sync();
            verify();
        }
        
        @Override
        public String toString() {
            return super.toString() + version; 
        }
    }


}
