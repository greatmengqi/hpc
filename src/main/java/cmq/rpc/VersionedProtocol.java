package cmq.rpc;

import java.io.IOException;

/**
 * @ProjectName: hpc
 * @Package: cmq.socket
 * @ClassName: VersionedProtocol
 * @Author: chenmengqi
 * @Description:
 * @Date: 2020/3/7 9:18 上午
 * @Version: 1.0
 */
public interface VersionedProtocol {
    /**
     * Return protocol version corresponding to protocol interface.
     *
     * @param protocol      The classname of the protocol interface
     * @param clientVersion The version of the protocol that the client speaks
     * @return the version that the server will speak
     */
    public long getProtocolVersion(String protocol,
                                   long clientVersion) throws IOException;
}
