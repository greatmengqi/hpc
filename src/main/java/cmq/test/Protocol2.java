package cmq.test;

import cmq.rpc.VersionedProtocol;

/**
 * @ProjectName: hadoop_learn
 * @Package: blm.rpc
 * @ClassName: Protocol
 * @Author: chenmengqi
 * @Description:
 * @Date: 2020/2/28 9:30 下午
 * @Version: 1.0
 */
public interface Protocol2 extends VersionedProtocol {
    public static final long versionID = 2L;

    public int sub(int a, int b);
}
