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

package org.apache.zookeeper.server;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.jute.Record;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.SetACLRequest;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.server.ZooKeeperServer.ChangeRecord;
import org.apache.zookeeper.server.auth.AuthenticationProvider;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This request processor is generally at the start of a RequestProcessor
 * change. It sets up any transactions associated with requests that change the
 * state of the system. It counts on ZooKeeperServer to update
 * outstandingRequests, so that it can take into account transactions that are
 * in the queue to be applied when generating a transaction.
 */
public class PrepRequestProcessor extends Thread implements RequestProcessor {
    private static final Logger LOG = Logger.getLogger(PrepRequestProcessor.class);

    static boolean skipACL;
    static {
        skipACL = System.getProperty("zookeeper.skipACL", "no").equals("yes");
        if (skipACL) {
            LOG.info("zookeeper.skipACL==\"yes\", ACL checks will be skipped");
        }
    }

    LinkedBlockingQueue<Request> submittedRequests = new LinkedBlockingQueue<Request>();

    RequestProcessor nextProcessor;

    ZooKeeperServer zks;

    public PrepRequestProcessor(ZooKeeperServer zks,
            RequestProcessor nextProcessor) {
        super("ProcessThread:" + zks.getClientPort());
        this.nextProcessor = nextProcessor;
        this.zks = zks;

        start();
    }

    @Override
    public void run() {
        try {
            while (true) {
                Request request = submittedRequests.take();
                long traceMask = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
                if (request.type == OpCode.ping) {
                    traceMask = ZooTrace.CLIENT_PING_TRACE_MASK;
                }
                ZooTrace.logRequest(LOG, traceMask, 'P', request, "");
                if (Request.requestOfDeath == request) {
                    break;
                }
                pRequest(request);
            }
        } catch (InterruptedException e) {
            LOG.error("FIXMSG",e);
        }
        ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(),
                "PrepRequestProcessor exited loop!");
    }

    ChangeRecord getRecordForPath(String path) throws KeeperException.NoNodeException {
        ChangeRecord lastChange = null;
        synchronized (zks.outstandingChanges) {
            for (int i = 0; i < zks.outstandingChanges.size(); i++) {
                ChangeRecord c = zks.outstandingChanges.get(i);
                if (c.path.equals(path)) {
                    lastChange = c;
                }
            }
            if (lastChange == null) {
                DataNode n = zks.dataTree.getNode(path);
                if (n != null) {
                    lastChange = new ChangeRecord(-1, path, n.stat, n.children
                            .size(), zks.dataTree.convertLong(n.acl));
                }
            }
        }
        if (lastChange == null || lastChange.stat == null) {
            throw new KeeperException.NoNodeException();
        }
        return lastChange;
    }

    void addChangeRecord(ChangeRecord c) {
        synchronized (zks.outstandingChanges) {
            zks.outstandingChanges.add(c);
        }
    }

    static void checkACL(ZooKeeperServer zks, List<ACL> acl, int perm,
            List<Id> ids) throws KeeperException.NoAuthException {
        if (skipACL) {
            return;
        }
        if (acl == null || acl.size() == 0) {
            return;
        }
        for (ACL a : acl) {
            Id id = a.getId();
            if ((a.getPerms() & perm) != 0) {
                if (id.getScheme().equals("world")
                        && id.getId().equals("anyone")) {
                    return;
                }
                AuthenticationProvider ap = ProviderRegistry.getProvider(id
                        .getScheme());
                if (ap != null) {
                    for (Id authId : ids) {
                        if (authId.getScheme().equals("super")) {
                            return;
                        }
                        if (authId.getScheme().equals(id.getScheme())
                                && ap.matches(authId.getId(), id.getId())) {
                            return;
                        }
                    }
                }
            }
        }
        throw new KeeperException.NoAuthException();
    }

    /**
     * This method will be called inside the ProcessRequestThread, which is a
     * singleton, so there will be a single thread calling this code.
     *
     * @param request
     */
    @SuppressWarnings("unchecked")
    protected void pRequest(Request request) {
        // LOG.info("Prep>>> cxid = " + request.cxid + " type = " +
        // request.type + " id = 0x" + Long.toHexString(request.sessionId));
        TxnHeader txnHeader = null;
        Record txn = null;
        try {
            switch (request.type) {
            case OpCode.create:
                txnHeader = new TxnHeader(request.sessionId, request.cxid, zks
                        .getNextZxid(), zks.getTime(), OpCode.create);
                zks.sessionTracker.checkSession(request.sessionId);
                CreateRequest createRequest = new CreateRequest();
                ZooKeeperServer.byteBuffer2Record(request.request,
                        createRequest);
                String path = createRequest.getPath();
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash == -1 || path.indexOf('\0') != -1) {
                    throw new KeeperException.BadArgumentsException();
                }
                if (!fixupACL(request.authInfo, createRequest.getAcl())) {
                    throw new KeeperException.InvalidACLException();
                }
                String parentPath = path.substring(0, lastSlash);
                ChangeRecord parentRecord = getRecordForPath(parentPath);

                checkACL(zks, parentRecord.acl, ZooDefs.Perms.CREATE,
                        request.authInfo);
                int parentCVersion = parentRecord.stat.getCversion();
                CreateMode createMode = CreateMode.fromFlag(createRequest.getFlags());
                if (createMode.isSequential()) {
                    path = path + String.format("%010d", parentCVersion);
                }
                try {
                    if (getRecordForPath(path) != null) {
                        throw new KeeperException.NodeExistsException();
                    }
                } catch (KeeperException.NoNodeException e) {
                    // ignore this one
                }
                boolean ephemeralParent = parentRecord.stat.getEphemeralOwner() != 0;
                if (ephemeralParent) {
                    throw new KeeperException.NoChildrenForEphemeralsException();
                }
                txn = new CreateTxn(path, createRequest.getData(),
                        createRequest.getAcl(),
                        createMode.isEphemeral());
                StatPersisted s = new StatPersisted();
                if (createMode.isEphemeral()) {
                    s.setEphemeralOwner(request.sessionId);
                }
                parentRecord = parentRecord.duplicate(txnHeader.getZxid());
                parentRecord.childCount++;
                parentRecord.stat
                        .setCversion(parentRecord.stat.getCversion() + 1);
                addChangeRecord(parentRecord);
                addChangeRecord(new ChangeRecord(txnHeader.getZxid(), path, s,
                        0, createRequest.getAcl()));

                break;
            case OpCode.delete:
                txnHeader = new TxnHeader(request.sessionId, request.cxid, zks
                        .getNextZxid(), zks.getTime(), OpCode.delete);
                zks.sessionTracker.checkSession(request.sessionId);
                DeleteRequest deleteRequest = new DeleteRequest();
                ZooKeeperServer.byteBuffer2Record(request.request,
                        deleteRequest);
                path = deleteRequest.getPath();
                lastSlash = path.lastIndexOf('/');
                if (lastSlash == -1 || path.indexOf('\0') != -1
                        || path.equals("/")) {
                    throw new KeeperException.BadArgumentsException();
                }
                parentPath = path.substring(0, lastSlash);
                parentRecord = getRecordForPath(parentPath);
                ChangeRecord nodeRecord = getRecordForPath(path);
                checkACL(zks, parentRecord.acl, ZooDefs.Perms.DELETE,
                        request.authInfo);
                int version = deleteRequest.getVersion();
                if (version != -1 && nodeRecord.stat.getVersion() != version) {
                    throw new KeeperException.BadVersionException();
                }
                if (nodeRecord.childCount > 0) {
                    throw new KeeperException.NotEmptyException();
                }
                txn = new DeleteTxn(path);
                parentRecord = parentRecord.duplicate(txnHeader.getZxid());
                parentRecord.childCount--;
                parentRecord.stat
                        .setCversion(parentRecord.stat.getCversion() + 1);
                addChangeRecord(parentRecord);
                addChangeRecord(new ChangeRecord(txnHeader.getZxid(), path,
                        null, -1, null));
                break;
            case OpCode.setData:
                txnHeader = new TxnHeader(request.sessionId, request.cxid, zks
                        .getNextZxid(), zks.getTime(), OpCode.setData);
                zks.sessionTracker.checkSession(request.sessionId);
                SetDataRequest setDataRequest = new SetDataRequest();
                ZooKeeperServer.byteBuffer2Record(request.request,
                        setDataRequest);
                path = setDataRequest.getPath();
                nodeRecord = getRecordForPath(path);
                checkACL(zks, nodeRecord.acl, ZooDefs.Perms.WRITE,
                        request.authInfo);
                version = setDataRequest.getVersion();
                int currentVersion = nodeRecord.stat.getVersion();
                if (version != -1 && version != currentVersion) {
                    throw new KeeperException.BadVersionException();
                }
                version = currentVersion + 1;
                txn = new SetDataTxn(path, setDataRequest.getData(), version);
                nodeRecord = nodeRecord.duplicate(txnHeader.getZxid());
                nodeRecord.stat.setVersion(version);
                addChangeRecord(nodeRecord);
                break;
            case OpCode.setACL:
                txnHeader = new TxnHeader(request.sessionId, request.cxid, zks
                        .getNextZxid(), zks.getTime(), OpCode.setACL);
                zks.sessionTracker.checkSession(request.sessionId);
                SetACLRequest setAclRequest = new SetACLRequest();
                ZooKeeperServer.byteBuffer2Record(request.request,
                        setAclRequest);
                if (!fixupACL(request.authInfo, setAclRequest.getAcl())) {
                    throw new KeeperException.InvalidACLException();
                }
                path = setAclRequest.getPath();
                nodeRecord = getRecordForPath(path);
                checkACL(zks, nodeRecord.acl, ZooDefs.Perms.ADMIN,
                        request.authInfo);
                version = setAclRequest.getVersion();
                currentVersion = nodeRecord.stat.getAversion();
                if (version != -1 && version != currentVersion) {
                    throw new KeeperException.BadVersionException();
                }
                version = currentVersion + 1;
                txn = new SetACLTxn(path, setAclRequest.getAcl(), version);
                nodeRecord = nodeRecord.duplicate(txnHeader.getZxid());
                nodeRecord.stat.setAversion(version);
                addChangeRecord(nodeRecord);
                break;
            case OpCode.createSession:
                txnHeader = new TxnHeader(request.sessionId, request.cxid, zks
                        .getNextZxid(), zks.getTime(), OpCode.createSession);
                request.request.rewind();
                int to = request.request.getInt();
                txn = new CreateSessionTxn(to);
                request.request.rewind();
                zks.sessionTracker.addSession(request.sessionId, to);
                break;
            case OpCode.closeSession:
                txnHeader = new TxnHeader(request.sessionId, request.cxid, zks
                        .getNextZxid(), zks.getTime(), OpCode.closeSession);
                HashSet<String> es = zks.dataTree
                        .getEphemerals(request.sessionId);
                synchronized (zks.outstandingChanges) {
                    for (ChangeRecord c : zks.outstandingChanges) {
                        if (c.stat == null) {
                            // Doing a delete
                            es.remove(c.path);
                        } else if (c.stat.getEphemeralOwner() == request.sessionId) {
                            es.add(c.path);
                        }
                    }
                    for (String path2Delete : es) {
                        addChangeRecord(new ChangeRecord(txnHeader.getZxid(),
                                path2Delete, null, 0, null));
                    }
                }
                LOG.info("Processed session termination request for id: 0x"
                        + Long.toHexString(request.sessionId));
                break;
            case OpCode.sync:
            case OpCode.exists:
            case OpCode.getData:
            case OpCode.getACL:
            case OpCode.getChildren:
            case OpCode.ping:
            case OpCode.setWatches:
                break;
            }
        } catch (KeeperException e) {
            if (txnHeader != null) {
                txnHeader.setType(OpCode.error);
                txn = new ErrorTxn(e.getCode());
            }
        } catch (Exception e) {
            LOG.error("*********************************" + request);
            StringBuffer sb = new StringBuffer();
            ByteBuffer bb = request.request;
            if(bb!=null){
                bb.rewind();
                while (bb.hasRemaining()) {
                    sb.append(Integer.toHexString(bb.get() & 0xff));
                }
            }else
                sb.append("request buffer is null");
            LOG.error(sb.toString());
            LOG.error("Unexpected exception", e);
            if (txnHeader != null) {
                txnHeader.setType(OpCode.error);
                txn = new ErrorTxn(Code.MarshallingError);
            }
        }
        request.hdr = txnHeader;
        request.txn = txn;
        request.zxid = zks.getZxid();
        nextProcessor.processRequest(request);
    }

    /**
     * This method checks out the acl making sure it isn't null or empty,
     * it has valid schemes and ids, and expanding any relative ids that
     * depend on the requestor's authentication information.
     *
     * @param authInfo list of ACL IDs associated with the client connection
     * @param acl list of ACLs being assigned to the node (create or setACL operation)
     * @return
     */
    private boolean fixupACL(List<Id> authInfo, List<ACL> acl) {
        if (skipACL) {
            return true;
        }
        if (acl == null || acl.size() == 0) {
            return false;
        }
        Iterator<ACL> it = acl.iterator();
        LinkedList<ACL> toAdd = null;
        while (it.hasNext()) {
            ACL a = it.next();
            Id id = a.getId();
            if (id.getScheme().equals("world") && id.getId().equals("anyone")) {
                // wide open
            } else if (id.getScheme().equals("auth")) {
                // This is the "auth" id, so we have to expand it to the
                // authenticated ids of the requestor
                it.remove();
                if (toAdd == null) {
                    toAdd = new LinkedList<ACL>();
                }
                boolean authIdValid = false;
                for (Id cid : authInfo) {
                    AuthenticationProvider ap = ProviderRegistry.getProvider(cid.getScheme());
                    if (ap == null) {
                        LOG.error("Missing AuthenticationProvider for "
                                + cid.getScheme());
                    } else if (ap.isAuthenticated()) {
                        authIdValid = true;
                        toAdd.add(new ACL(a.getPerms(), cid));
                    }
                }
                if (!authIdValid) {
                    return false;
                }
            } else {
                AuthenticationProvider ap = ProviderRegistry.getProvider(id
                        .getScheme());
                if (ap == null) {
                    return false;
                }
                if (!ap.isValid(id.getId())) {
                    return false;
                }
            }
        }
        if (toAdd != null) {
            for (ACL a : toAdd) {
                acl.add(a);
            }
        }
        return acl.size() > 0;
    }

    public void processRequest(Request request) {
        // request.addRQRec(">prep="+zks.outstandingChanges.size());
        submittedRequests.add(request);
    }

    public void shutdown() {
        submittedRequests.clear();
        submittedRequests.add(Request.requestOfDeath);
        nextProcessor.shutdown();
    }
}
