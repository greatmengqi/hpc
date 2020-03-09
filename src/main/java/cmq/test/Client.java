package cmq.test;


import cmq.rpc.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * @ProjectName: hadoop_learn
 * @Package: blm.rpc
 * @ClassName: Server
 * @Author: chenmengqi
 * @Description:
 * @Date: 2020/2/28 9:31 下午
 * @Version: 1.0
 */
public class Client {
    public static void main(String[] args) throws IOException {
        while (true) {
            // 通过协议一和服务器通信,这里扮演角色一
            Protocol1 proxy1 = (Protocol1) RPC.getProxy(Protocol1.class, Protocol1.versionID, new InetSocketAddress(8800));
            System.out.println(proxy1.add(2, 1));
            System.out.println(proxy1.add(2, 1));

            // 通过协议二和服务器通信,这里扮演角色二
            Protocol2 proxy2 = (Protocol2) RPC.getProxy(Protocol2.class, Protocol2.versionID, new InetSocketAddress(8800));
            System.out.println(proxy2.sub(2, 1));
            System.out.println(proxy2.sub(3, 1));
        }
    }
}
