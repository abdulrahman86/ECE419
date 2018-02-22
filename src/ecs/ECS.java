package ecs;

import app_kvECS.IECSClient;
import com.google.gson.Gson;
import common.messages.KVAdminMessage;
import org.apache.log4j.Logger;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import server.ServerMetaData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * This class handles core functionality of external configuration service
 */
public class ECS implements IECSClient {
    private static final String SERVER_JAR = "KVServer.jar";
    // Assumes that the jar file is located at the same dir on the remote server
    private static final String JAR_PATH = new File(System.getProperty("user.dir"), SERVER_JAR).toString();
    // Assumes that ZooKeeper runs on localhost default port(2181)
    public static final String ZK_HOST = "127.0.0.1";
    public static final String ZK_PORT = "2181";
    private static final String ZK_CONN = ZK_HOST + ":" + ZK_PORT;

    // ZooKeeper connection timeout in millisecond
    public static final int ZK_TIMEOUT = 2000;

    public static final String ZK_SERVER_ROOT = "/kv_servers";
    public static final String ZK_METADATA_ROOT = "/metadata";

    private static Logger logger = Logger.getRootLogger();

    /**
     * Roster of all <b>idle<b/> storage service nodes
     */
    private Queue<IECSNode> nodePool = new ConcurrentLinkedQueue<>();

    /**
     * Roster of all service that are initialized(assigned cacheStrategy and cacheSize)
     */
    private Map<String, IECSNode> nodeTable = new HashMap<>();

    /**
     * HashRing object responsible to update the hash range of each ECSNode
     */
    private ECSHashRing hashRing = new ECSHashRing();

    /**
     * ZooKeeper instance used to communicate with zk server
     */
    private ZooKeeper zk;

    public class ECSConfigFormatException extends RuntimeException {
        public ECSConfigFormatException(String msg) {
            super(msg);
        }
    }

    public ECS(String configFileName) throws IOException {
        BufferedReader configReader = new BufferedReader(new FileReader(new File(configFileName)));

        String currentLine;
        Set<String> namePool = new HashSet<>();
        while ((currentLine = configReader.readLine()) != null) {
            String[] tokens = currentLine.split(" ");
            if (tokens.length != 3) {
                throw new ECSConfigFormatException("invalid number of arguments! should be 3 but got " +
                        tokens.length + ".");
            }
            String name = tokens[0];
            String ip = tokens[1];
            Integer port = Integer.parseInt(tokens[2]);
            if (namePool.contains(name)) {
                logger.warn(name + " already exists. Server name must be unique, please check for duplications");
            } else {
                ECSNode newNode = new ECSNode(name, ip, port);
                nodePool.add(newNode);
                namePool.add(name);
                logger.info(newNode + " added to node pool");
            }
        }

        CountDownLatch sig = new CountDownLatch(0);
        zk = new ZooKeeper(ZK_CONN, ZK_TIMEOUT, event -> {
            if (event.getState().equals(Watcher.Event.KeeperState.SyncConnected)) {
                // connection fully established can proceed
                sig.countDown();
            }
        });
        try {
            sig.await();
        } catch (InterruptedException e) {
            // Should never happen
            e.printStackTrace();
        }
    }

    @Override
    public boolean start() throws Exception {
        List<ECSNode> toStart = new ArrayList<>();
        for (Map.Entry<String, IECSNode> entry : nodeTable.entrySet()) {
            ECSNode n = (ECSNode) entry.getValue();
            if (n.getStatus().equals(ECSNode.ServerStatus.STOP)) {
                toStart.add(n);
            }
        }
        ECSMulticaster multicaster = new ECSMulticaster(zk, toStart);
        boolean ret = multicaster.send(new KVAdminMessage(KVAdminMessage.OperationType.START));

        for (ECSNode n : toStart) {
            if (!multicaster.getErrors().keySet().contains(n)) {
                hashRing.addNode(n);
            }
        }
        toStart.forEach(n -> n.setStatus(ECSNode.ServerStatus.ACTIVE));

        updateMetadata();
        return ret;
    }

    @Override
    public boolean stop() throws Exception {
        List<ECSNode> toStop = new ArrayList<>();
        for (Map.Entry<String, IECSNode> entry : nodeTable.entrySet()) {
            ECSNode n = (ECSNode) entry.getValue();
            if (n.getStatus().equals(ECSNode.ServerStatus.ACTIVE)) {
                toStop.add(n);
            }
        }
        ECSMulticaster multicaster = new ECSMulticaster(zk, toStop);
        boolean ret = multicaster.send(new KVAdminMessage(KVAdminMessage.OperationType.STOP));

        for (ECSNode n : toStop) {
            if (!multicaster.getErrors().keySet().contains(n)) {
                hashRing.removeNode(n);
            }
        }
        toStop.forEach(n -> n.setStatus(ECSNode.ServerStatus.STOP));

        updateMetadata();
        return ret;
    }

    @Override
    public boolean shutdown() throws Exception {
        ECSMulticaster multicaster = new ECSMulticaster(zk, nodeTable.values()
                .stream().map((n) -> (ECSNode) n).collect(Collectors.toList()));
        boolean ret = multicaster.send(new KVAdminMessage(KVAdminMessage.OperationType.STOP));
        if (ret) {
            hashRing.removeAll();
            // TODO update node status
            ret = updateMetadata();
        }
        return ret;
    }

    static String getNodePath(IECSNode node) {
        return ZK_SERVER_ROOT + "/" + node.getNodeName();
    }

    @Override
    public IECSNode addNode(String cacheStrategy, int cacheSize) {
        Collection<IECSNode> nodes = addNodes(1, cacheStrategy, cacheSize);
        if (nodes == null) return null;
        assert nodes.size() <= 1;
        return nodes.size() == 1 ? (IECSNode) nodes.toArray()[0] : null;
    }

    @Override
    public Collection<IECSNode> addNodes(int count, String cacheStrategy, int cacheSize) {
        Collection<IECSNode> nodes = setupNodes(count, cacheStrategy, cacheSize);
        if (nodes == null) return null;

        for (IECSNode n : nodes) {
            assert n != null;
            // Issue the ssh call to start the process remotely
            String javaCmd = String.join(" ",
                    "java -jar",
                    JAR_PATH,
                    n.getNodeName(),
                    ZK_HOST,
                    ZK_PORT);
            String sshCmd = "ssh -o StrictHostKeyChecking=no -n " + n.getNodeHost() + " nohup " + javaCmd +
                    " > ./logs/output.log 2> ./logs/err.log &";
            // Redirect output to files so that ssh channel will not wait for further output
            try {
                logger.info("Executing command: " + sshCmd);
                Process p = Runtime.getRuntime().exec(sshCmd);
                p.waitFor();
                assert !p.isAlive();
                assert p.exitValue() == 0;
            } catch (IOException e) {
                logger.error("Unable to launch server with ssh (" + n + ")", e);
                e.printStackTrace();
                nodes.remove(n); // Connection failed, remove instance from result collection
            } catch (InterruptedException e) {
                logger.error("Receive an interrupt", e);
                e.printStackTrace();
                nodes.remove(n);
            }
        }

        for (IECSNode n : nodes) {
            ((ECSNode) n).setStatus(ECSNode.ServerStatus.INACTIVE);
            nodeTable.put(n.getNodeName(), n);
        }

        boolean ack;
        try {
            ack = awaitNodes(count, ZK_TIMEOUT);
        } catch (Exception e) {
            logger.error(e);
            return null;
        }

        if (ack) {
            nodes.forEach(n -> ((ECSNode) n).setStatus(ECSNode.ServerStatus.STOP));
            return nodes;
        } else {
            logger.error("Failed to receive ack from some servers");
            return null;
        }
    }

    @Override
    public Collection<IECSNode> setupNodes(int count, String cacheStrategy, int cacheSize) {
        if (count > nodePool.size()) return null;

        List<IECSNode> nodeList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ECSNode n = (ECSNode) nodePool.poll();
            nodeList.add(n);
        }

        byte[] metadata = new Gson().toJson(new ServerMetaData(cacheStrategy, cacheSize)).getBytes();

        // create corresponding Z-nodes on zookeeper server
        try {
            if (zk.exists(ZK_SERVER_ROOT, false) == null) {
                zk.create(ZK_SERVER_ROOT, "".getBytes(),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            for (IECSNode n : nodeList) {
                Stat exists = zk.exists(getNodePath(n), false);
                if (exists == null) {
                    zk.create(getNodePath(n), metadata,
                            ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } else {
                    zk.setData(getNodePath(n), metadata, exists.getVersion());
                    // Delete all children (msg z-nodes)
                    List<String> children = zk.getChildren(getNodePath(n), false);
                    for (String zn : children) {
                        String msgPath = getNodePath(n) + "/" + zn;
                        Stat ex = zk.exists(msgPath, false);
                        zk.delete(msgPath, ex.getVersion());
                    }
                }
            }
        } catch (InterruptedException | KeeperException e) {
            logger.error("Issue encountered with ZooKeeper server");
            e.printStackTrace();
            return null;
        }
        return nodeList;
    }

    @Override
    public boolean awaitNodes(int count, int timeout) throws Exception {
        List<ECSNode> toWait = nodeTable.values().stream()
                .map((n) -> (ECSNode) n)
                .filter((n) -> n.getStatus().equals(ECSNode.ServerStatus.INACTIVE))
                .collect(Collectors.toList());

        ECSMulticaster multicaster = new ECSMulticaster(zk, toWait);
        return multicaster.send(new KVAdminMessage(KVAdminMessage.OperationType.INIT));
    }

    @Override
    public boolean removeNodes(Collection<String> nodeNames) {
        List<ECSNode> toRemove = nodeTable.values().stream()
                .map((n) -> (ECSNode) n)
                .filter(n -> nodeNames.contains(n.getNodeName()))
                .collect(Collectors.toList());

        ECSMulticaster multicaster = new ECSMulticaster(zk, toRemove);
        boolean ret;
        try {
            ret = multicaster.send(new KVAdminMessage(KVAdminMessage.OperationType.SHUT_DOWN));
        } catch (InterruptedException e) {
            e.printStackTrace();
            ret = false;
        }

        if (ret) {
            toRemove.forEach(n -> hashRing.removeNode(n));
            ret = updateMetadata();
        }
        return ret;
    }

    @Override
    public Map<String, IECSNode> getNodes() {
        return nodeTable;
    }

    @Override
    public IECSNode getNodeByKey(String key) {
        return hashRing.getNodeByKey(key);
    }

    /**
     * Convert currently active node to an json array
     *
     * @return Json Array
     */
    private String getHashRingJson() {
        List<ECSNode> activeNodes = nodeTable.values().stream()
                .map(n -> (ECSNode) n)
                .filter(n -> n.getStatus().equals(ECSNode.ServerStatus.ACTIVE))
                .collect(Collectors.toList());

        return new Gson().toJson(activeNodes);
    }

    /**
     * Push the metadata(hash ring content) to ZooKeeper z-node
     */
    private boolean updateMetadata() {
        try {
            Stat exists = zk.exists(ZK_METADATA_ROOT, false);
            if (exists == null) {
                zk.create(ZK_METADATA_ROOT, getHashRingJson().getBytes(),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            } else {
                zk.setData(ZK_METADATA_ROOT, getHashRingJson().getBytes(),
                        exists.getVersion());
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted");
            return false;
        } catch (KeeperException e) {
            logger.error(e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean rearrangeDataStorage(Collection<ECSNode> nodes) {
        return false;
    }

    private void transferData(ECSNode from, ECSNode to, String[] hashRange) {

    }
}
