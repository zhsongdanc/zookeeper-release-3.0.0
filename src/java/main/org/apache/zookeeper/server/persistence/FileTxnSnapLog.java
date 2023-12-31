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

package org.apache.zookeeper.server.persistence;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jute.Record;
import org.apache.log4j.Logger;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This is a helper class 
 * above the implementations 
 * of txnlog and snapshot 
 * classes
 */
public class FileTxnSnapLog {
    //the direcotry containing the 
    //the transaction logs
    File dataDir; 
    //the directory containing the 
    //the snapshot directory
    File snapDir;
    TxnLog txnLog;
    SnapShot snapLog;
    public final static int VERSION = 2;
    public final static String version = "version-";
    
    private static final Logger LOG = Logger.getLogger(FileTxnSnapLog.class);
    
    /**
     * This listener helps
     * the external apis calling
     * restore to gather information
     * while the data is being 
     * restored.
     */
    public interface PlayBackListener {
        void onTxnLoaded(TxnHeader hdr, Record rec);
    }
    
    /**
     * the constructor which takes the datadir and 
     * snapdir.
     * @param dataDir the trasaction directory
     * @param snapDir the snapshot directory
     */
    public FileTxnSnapLog(File dataDir, File snapDir) {
        this.dataDir = new File(dataDir, version + VERSION);
        this.snapDir = new File(snapDir, version + VERSION);
        if (!this.dataDir.exists()) {
            this.dataDir.mkdirs();
        }
        if (!this.snapDir.exists()) {
            this.snapDir.mkdirs();
        }
        txnLog = new FileTxnLog(this.dataDir);
        snapLog = new FileSnap(this.snapDir);
    }
    
    /**
     * this function restors the server 
     * database after reading from the 
     * snapshots and transaction logs
     * @param dt the datatree to be restored
     * @param sessions the sessions to be restored
     * @param listener the playback listener to run on the 
     * database restoration
     * @return the highest zxid restored
     * @throws IOException
     */
    public long restore(DataTree dt, Map<Long, Integer> sessions, 
            PlayBackListener listener) throws IOException {
        snapLog.deserialize(dt, sessions);
        FileTxnLog txnLog = new FileTxnLog(dataDir);
        TxnIterator itr = txnLog.read(dt.lastProcessedZxid);
        long highestZxid = dt.lastProcessedZxid;
        TxnHeader hdr;
        while (true) {
            // iterator points to 
            // the first valid txn when initialized
            hdr = itr.getHeader();
            if (hdr == null) {
                //empty logs 
                return dt.lastProcessedZxid;
            }
            if (hdr.getZxid() < highestZxid && highestZxid != 0) {
                LOG.error(highestZxid + "(higestZxid) > "
                        + hdr.getZxid() + "(next log) for type "
                        + hdr.getType());
            } else {
                highestZxid = hdr.getZxid();
            }
            processTransaction(hdr,dt,sessions, itr.getTxn());
            if (!itr.next()) 
                break;
        }
        return highestZxid;
    }
    
    /**
     * process the transaction on the datatree
     * @param hdr the hdr of the transaction
     * @param dt the datatree to apply transaction to
     * @param sessions the sessions to be restored
     * @param txn the transaction to be applied
     */
    private void processTransaction(TxnHeader hdr,DataTree dt,
            Map<Long, Integer> sessions, Record txn){
        switch (hdr.getType()) {
        case OpCode.createSession:
            sessions.put(hdr.getClientId(),
                    ((CreateSessionTxn) txn).getTimeOut());
            ZooTrace.logTraceMessage(LOG,ZooTrace.SESSION_TRACE_MASK,
                    "playLog --- create session in log: "
                            + Long.toHexString(hdr.getClientId())
                            + " with timeout: "
                            + ((CreateSessionTxn) txn).getTimeOut());
            // give dataTree a chance to sync its lastProcessedZxid
            dt.processTxn(hdr, txn);
            break;
        case OpCode.closeSession:
            sessions.remove(hdr.getClientId());
            ZooTrace.logTraceMessage(LOG,ZooTrace.SESSION_TRACE_MASK,
                    "playLog --- close session in log: "
                            + Long.toHexString(hdr.getClientId()));
            dt.processTxn(hdr, txn);
            break;
        default:
            dt.processTxn(hdr, txn);
        }        
    }
    
    /**
     * the last logged zxid on the transaction logs
     * @return the last logged zxid
     */
    public long getLastLoggedZxid() {
        FileTxnLog txnLog = new FileTxnLog(dataDir);
        return txnLog.getLastLoggedZxid();
    }

    /**
     * save the datatree and the sessions into a snapshot
     * @param dataTree the datatree to be serialized onto disk
     * @param sessionsWithTimeouts the sesssion timeouts to be
     * serialized onto disk
     * @throws IOException
     */
    public void save(DataTree dataTree,
            ConcurrentHashMap<Long, Integer> sessionsWithTimeouts)
        throws IOException {
        long lastZxid = dataTree.lastProcessedZxid;
        LOG.info("Snapshotting: " + Long.toHexString(lastZxid));
        File snapshot=new File(
                snapDir, Util.makeSnapshotName(lastZxid));
        snapLog.serialize(dataTree, sessionsWithTimeouts, snapshot);
        
    }

    /**
     * truncate the transaction logs the zxid
     * specified
     * @param zxid the zxid to truncate the logs to
     * @return true if able to truncate the log, false if not
     * @throws IOException
     */
    public boolean truncateLog(long zxid) throws IOException {
        FileTxnLog txnLog = new FileTxnLog(dataDir);
        return txnLog.truncate(zxid);
    }
    
    /**
     * the most recent snapshot in the snapshot
     * directory
     * @return the file that contains the most 
     * recent snapshot
     * @throws IOException
     */
    public File findMostRecentSnapshot() throws IOException {
        FileSnap snaplog = new FileSnap(snapDir);
        return snaplog.findMostRecentSnapshot();
    }

    /**
     * get the snapshot logs that are greater than
     * the given zxid 
     * @param zxid the zxid that contains logs greater than 
     * zxid
     * @return
     */
    public File[] getSnapshotLogs(long zxid) {
        return FileTxnLog.getLogFiles(dataDir.listFiles(), zxid);
    }

    /**
     * append the request to the transaction logs
     * @param si the request to be appended
     * @throws IOException
     */
    public void append(Request si) throws IOException {
        txnLog.append(si.hdr, si.txn);
    }

    /**
     * commit the transaction of logs
     * @throws IOException
     */
    public void commit() throws IOException {
        txnLog.commit();
    }

    /**
     * roll the transaction logs
     */
    public void rollLog() {
        txnLog.rollLog();
    }
    
}
