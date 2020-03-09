package cmq.rpc;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;

/**
 * @ProjectName: hpc
 * @Package: cmq.socket
 * @ClassName: RPC
 * @Author: chenmengqi
 * @Description: null
 * @Date: 2020/3/7 8:58 下午
 * @Version: 1.0
 */
public class RPC {

    public static VersionedProtocol getProxy(
            Class<? extends VersionedProtocol> protocol,
            long clientVersion, InetSocketAddress addr) throws IOException {

        VersionedProtocol proxy =
                (VersionedProtocol) Proxy.newProxyInstance(
                        protocol.getClassLoader(), new Class[]{protocol},
                        new Invoker(protocol, addr));
        long serverVersion = proxy.getProtocolVersion(protocol.getName(),
                clientVersion);
        if (serverVersion == clientVersion) {
            return proxy;
        } else {
            throw new VersionMismatch(protocol.getName(), clientVersion,
                    serverVersion);
        }
    }

    private static class Invoker implements InvocationHandler {
        private Client.ConnectionId remoteId;
        private Class<? extends VersionedProtocol> protocol;
        private Client client;


        public Invoker(Class<? extends VersionedProtocol> protocol,
                       InetSocketAddress address) throws IOException {
            this.remoteId = Client.ConnectionId.getConnectionId(address, protocol);
            this.client = new Client();
            this.protocol = protocol;
        }

        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            return client.call(method.getName(), args, method.getParameterTypes(), remoteId, protocol);
        }
    }

    public static class VersionMismatch extends IOException {
        private String interfaceName;
        private long clientVersion;
        private long serverVersion;

        /**
         * Create a version mismatch exception
         *
         * @param interfaceName the name of the protocol mismatch
         * @param clientVersion the client's version of the protocol
         * @param serverVersion the server's version of the protocol
         */
        public VersionMismatch(String interfaceName, long clientVersion,
                               long serverVersion) {
            super("Protocol " + interfaceName + " version mismatch. (client = " +
                    clientVersion + ", server = " + serverVersion + ")");
            this.interfaceName = interfaceName;
            this.clientVersion = clientVersion;
            this.serverVersion = serverVersion;
        }

        /**
         * Get the interface name
         *
         * @return the java class name
         * (eg. org.apache.hadoop.mapred.InterTrackerProtocol)
         */
        public String getInterfaceName() {
            return interfaceName;
        }

        /**
         * Get the client's preferred version
         */
        public long getClientVersion() {
            return clientVersion;
        }

        /**
         * Get the server's agreed to version.
         */
        public long getServerVersion() {
            return serverVersion;
        }
    }


    public static Server getServer(final Object instance, final String bindAddress, final int port)
            throws IOException {
        return new Server(instance, port, bindAddress);
    }
}
