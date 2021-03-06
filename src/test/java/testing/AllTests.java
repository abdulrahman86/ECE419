package testing;

import app_kvServer.KVServer;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import logger.LogSetup;
import org.apache.log4j.Level;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;


public class AllTests {

    static {
        try {
            new LogSetup("logs/testing/test.log", Level.ALL);
            KVServer server = new KVServer(50099, 10, "FIFO", "TestIterateDB");
            server.clearStorage();
            new Thread(server).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static Test suite() {
        TestSuite clientSuite = new TestSuite("Basic Storage ServerTest-Suite");
        List<Class<? extends TestCase>> tests = Arrays.asList(
                ConnectionTest.class,
                InteractionTest.class,
                FIFOCacheTest.class,
                LFUCacheTest.class,
                LRUCacheTest.class,
                PersistentStoreTest.class,
                HashRingTest.class,
                ECSBasicTest.class,
                ECSLoadTest.class,
                //ServerTest.class,
                DataDistributionManagerTest.class,
                SQLTableTest.class,
                SQLExecutorTest.class,
                SQLStoreTest.class
        );
        for (Class<? extends TestCase> test :
                tests) {
            clientSuite.addTestSuite(test);
        }
        return clientSuite;
    }
}
