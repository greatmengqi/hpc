package cmq.test;

import cmq.rpc.RPC;

import java.io.IOException;

/**
 * @ProjectName: hadoop_learn
 * @Package: blm.rpc
 * @ClassName: Server
 * @Author: chenmengqi
 * @Description:
 * @Date: 2020/2/28 9:31 下午
 * @Version: 1.0
 */
public class Server implements Protocol1, Protocol2 {
    @Override
    public long getProtocolVersion(String protocol, long clientVersion) throws IOException {
        if (protocol.equals(Protocol1.class.getName())) {
            return Protocol1.versionID;
        } else if (protocol.equals(Protocol2.class.getName())) {
            return Protocol2.versionID;
        } else {
            throw new IOException("Unknown protocol to job tracker: " + protocol);
        }
    }

    // 实现协议一的方法
    @Override
    public int add(int a, int b) {
        return a + b;
    }

    // 实习协议二的方法
    @Override
    public int sub(int a, int b) {
        return a - b;
    }

    public void createServer() throws IOException {
        cmq.rpc.Server server = RPC.getServer(this, "127.0.0.1", 8800);
        server.start();
    }

    public static void main(String[] args) throws IOException {
        Server server = new Server();
        server.createServer();
    }
}
