package cmq.util;

/**
 * @ProjectName: hpc
 * @Package: cmq.util
 * @ClassName: ByteUtil
 * @Author: chenmengqi
 * @Description: null
 * @Date: 2020/3/7 7:14 下午
 * @Version: 1.0
 */
public class ByteUtil {
    public static byte[] toByteArray(int source, int len) {
        byte[] target = new byte[len];
        for (int i = 0; i < 4 && i < len; i++) {
            target[i] = (byte) (source >> 8 * i & 0xff);
        }
        return target;
    }

    public static int toInt(byte[] source) {
        int target = 0;
        for (int i = 0; i < source.length; i++) {
            target += (source[i] & 0xff) << 8 * i;
        }
        return target;
    }
}
