package org.apache.zookeeper.demus;

import org.apache.zookeeper.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class ZooKeeperClient {
    private static final int SESSION_TIMEOUT = 50000;
    private static final String CONNECT_STRING = "localhost:2181,localhost:2183";
    private static final String PARENT_NODE = "/test";
    private static final String CHILD_NODE = "/test/child";

    public static void main(String[] args) throws IOException, InterruptedException, KeeperException {
        final CountDownLatch connectedSignal = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper(CONNECT_STRING, SESSION_TIMEOUT, new Watcher() {
            public void process(WatchedEvent we) {
                if (we.getState() == Event.KeeperState.SyncConnected) {
                    connectedSignal.countDown();
                    System.out.println("---------connect");
                }
            }
        });
        connectedSignal.await();

        // 创建父节点
        if (zk.exists(PARENT_NODE, false) == null) {
            zk.create(PARENT_NODE, "parent".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        System.out.println("创建父节点");

        // 创建子节点
        if (zk.exists(CHILD_NODE, false) == null) {
            zk.create(CHILD_NODE, "child".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        System.out.println("创建子节点");


        // 读取子节点数据
        byte[] data = zk.getData(CHILD_NODE, false, null);
        System.out.println("Child node data: " + new String(data));


        // 更新子节点数据
        zk.setData(CHILD_NODE, "new child".getBytes(), -1);

        // 读取子节点数据
        data = zk.getData(CHILD_NODE, false, null);
        System.out.println("Updated child node data: " + new String(data));

        // 删除子节点
        zk.delete(CHILD_NODE, -1);
        System.out.println("删除父节点");


        // 删除父节点
        zk.delete(PARENT_NODE, -1);
        System.out.println("删除父节点");


        // 关闭ZooKeeper客户端连接
        zk.close();
    }
}
