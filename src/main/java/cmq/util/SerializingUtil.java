package cmq.util;

import com.dyuproject.protostuff.LinkedBuffer;
import com.dyuproject.protostuff.ProtostuffIOUtil;
import com.dyuproject.protostuff.Schema;
import com.dyuproject.protostuff.runtime.RuntimeSchema;

/**
 * @ProjectName: hpc
 * @Package: cmq.serious
 * @ClassName: SerializingUtil
 * @Author: chenmengqi
 * @Description:
 * @Date: 2020/3/6 10:51 下午
 * @Version: 1.0
 */

public class SerializingUtil {
    @SuppressWarnings("unchecked")
    public static byte[] serialize(Object object) {
        SerializeData serializeData = new SerializeData();
        serializeData.target = object;
        Class<SerializeData> serializeDataClass = (Class<SerializeData>) serializeData.getClass();
        LinkedBuffer linkedBuffer = LinkedBuffer.allocate(1024);
        try {
            Schema<SerializeData> schema = RuntimeSchema.getSchema(serializeDataClass);
            return ProtostuffIOUtil.toByteArray(serializeData, schema, linkedBuffer);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            linkedBuffer.clear();
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T deserialize(byte[] data, Class<T> clazz) {
        try {
            Schema<SerializeData> schema = RuntimeSchema.getSchema(SerializeData.class);
            SerializeData serializeData = schema.newMessage();
            ProtostuffIOUtil.mergeFrom(data, serializeData, schema);
            return (T) serializeData.target;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static class SerializeData {
        private Object target;
    }
}
