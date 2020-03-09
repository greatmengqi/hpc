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
public interface Protocol1 extends VersionedProtocol {
    public static final long versionID = 1L;
    public int add(int a, int b);

}
