package testing;

import app_kvServer.KVServer;
import ecs.ECS;
import ecs.IECSNode;
import junit.framework.TestCase;
import logger.LogSetup;
import org.apache.log4j.Level;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

public class ECSTest extends TestCase {
    private static final Integer BIG_SERVER_NUM = 1024 * 1024;
    private static final String CACHE_STRATEGY = "FIFO";
    private static final Integer CACHE_SIZE = 1024;

    public void setUp() throws Exception {
        super.setUp();
        if (!LogSetup.isActive()) {
            new LogSetup("./logs/test_ecs.log", Level.ALL);
        }
    }

    private static ECS ecs = null;
    private Exception ex = null;

    @Before
    public void preTest() {
        ex = null;
    }

    public void testCreation() throws IOException {
        ecs = new ECS("./ecs.config");
        new ECS("./test_instances/ecs_dup_name.config");
        try {
            new ECS("./test_instances/ecs_bad_format.config");
        } catch (Exception e) {
            ex = e;
        }
        assertNotNull(ex);
    }

    /**
     * This testcase is no longer in use because ssh-ing to localhost and create
     * server instances is high un-debug-able
     */
    /*public void testAddNodes() {
        IECSNode node = ecs.addNode(CACHE_STRATEGY, CACHE_SIZE);
        assertNotNull(node);

        Collection<IECSNode> nodes =
                ecs.addNodes(2, CACHE_STRATEGY, CACHE_SIZE);

        assertNotNull(nodes);
        assertEquals(2, nodes.size());

        nodes = ecs.addNodes(BIG_SERVER_NUM, CACHE_STRATEGY, CACHE_SIZE);
        assertNull(nodes);
    }*/

    public void testAddNodes() throws Exception {
        Integer count = 3;
        Collection<IECSNode> nodes =
                ecs.setupNodes(count, CACHE_STRATEGY, CACHE_SIZE);

        assertNotNull(nodes);
        assertEquals(count, new Integer(nodes.size()));

        // Start the servers internally
        for (IECSNode node : nodes) {
            new Thread(
                    new KVServer(node.getNodeName(), ECS.ZK_HOST, Integer.parseInt(ECS.ZK_PORT)))
                    .start();
        }

        boolean ret = ecs.awaitNodes(count, ECS.ZK_TIMEOUT);
        assertTrue(ret);
    }
}
