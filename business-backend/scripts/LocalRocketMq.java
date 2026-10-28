// Test-only standalone Broker/NameServer. Compile with the broker-test profile's classpath.
// Writes only to the explicitly supplied test directory; not part of the application JAR.
import java.nio.file.Path;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.common.BrokerConfig;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.namesrv.NamesrvController;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.store.config.MessageStoreConfig;

public class LocalRocketMq {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 4) throw new IllegalArgumentException("test-directory advertised-broker-ip [nameserver-port broker-port]");
        int nameserverPort = args.length == 4 ? Integer.parseInt(args[2]) : 19876;
        int brokerPort = args.length == 4 ? Integer.parseInt(args[3]) : 11911;
        String root = Path.of(args[0]).toAbsolutePath().toString();
        // BrokerStartup normally sets this. Omitting it selects the legacy registration body decoder.
        System.setProperty("rocketmq.remoting.version", Integer.toString(MQVersion.CURRENT_VERSION));
        System.setProperty("rocketmq.client.logRoot", root);
        System.setProperty("rocketmq.namesrv.logRoot", root);
        System.setProperty("rocketmq.broker.logRoot", root);
        var namesrvNetty = new NettyServerConfig();
        namesrvNetty.setListenPort(nameserverPort);
        var namesrv = new NamesrvController(new NamesrvConfig(), namesrvNetty);
        if (!namesrv.initialize()) throw new IllegalStateException("NameServer initialization failed");
        namesrv.start();
        var config = new BrokerConfig();
        config.setBrokerName("vtrace-isolated-test");
        config.setNamesrvAddr("127.0.0.1:" + nameserverPort);
        config.setBrokerIP1(args[1]);
        config.setBrokerIP2(args[1]);
        config.setAutoCreateTopicEnable(true);
        var server = new NettyServerConfig();
        server.setListenPort(brokerPort);
        var store = new MessageStoreConfig();
        store.setStorePathRootDir(root);
        store.setStorePathCommitLog(Path.of(root, "commitlog").toString());
        store.setMappedFileSizeCommitLog(16 * 1024 * 1024);
        store.setHaListenPort(brokerPort + 1);
        var broker = new BrokerController(config, server, new NettyClientConfig(), store);
        if (!broker.initialize()) throw new IllegalStateException("Broker initialization failed");
        // Provision the exact existing topic explicitly instead of relying on auto-create.
        broker.getTopicConfigManager().updateTopicConfig(new TopicConfig("video-analysis-topic", 4, 4, 6));
        broker.start();
        broker.registerBrokerAll(true, false, true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { broker.shutdown(); namesrv.shutdown(); }));
        System.out.println("VTRACE_TEST_BROKER_READY nameserver=" + nameserverPort + " broker=" + brokerPort);
        System.out.println("VTRACE_TEST_TOPIC_ROUTE " + namesrv.getRouteInfoManager().pickupTopicRouteData("video-analysis-topic"));
    }
}
