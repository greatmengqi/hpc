package cmq.rpc;

import jdk.nashorn.internal.codegen.CompilerConstants;

import java.util.Arrays;

/**
 * 用于简化传输代码的编写,客户端到服务端沟通媒介
 * 客户端将其序列化传后传给服务器，服务器将其反序列化后进行计算，
 * 计算的结果在序列化传给客户端，客户端反序列化拿到结果
 *
 * @ProjectName: hpc
 * @Package: cmq.socket
 * @ClassName: Message
 * @Author: chenmengqi
 * @Description:
 * @Date: 2020/3/6 11:13 下午
 * @Version: 1.0
 */
public class Message {

    private String protocolName;

    // 这个id要和Call的id保持一致，当处理完Message后可以通过ID找到对应的call
    public int id;

    // 客户端调用的方法
    private String methodName;
    // 客户端调用的参数的class,因为参数是object数组，后面要强制转换
    private Class[] parameterClasses;
    // 客户端调用的参数的具体内容
    private Object[] parameters;


    // message的执行结果，如果执行不正常response
    public Status status;
    private Object response;
    public Class responseClass;
    private Exception e;

    public String getProtocolName() {
        return protocolName;
    }

    public void setProtocolName(String protocolName) {
        this.protocolName = protocolName;
    }

    public Message(String methodName, Class[] parameterClasses, Object[] parameters, String protocolName) {
        this.methodName = methodName;
        this.parameterClasses = parameterClasses;
        this.parameters = parameters;
        this.protocolName = protocolName;
    }

    public Exception getE() {
        return e;
    }

    public void setE(Exception e) {
        this.e = e;
    }

    public Message(CompilerConstants.Call call) {

    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public Class[] getParameterClasses() {
        return parameterClasses;
    }

    public void setParameterClasses(Class[] parameterClasses) {
        this.parameterClasses = parameterClasses;
    }

    public Object[] getParameters() {
        return parameters;
    }

    public void setParameters(Object[] parameters) {
        this.parameters = parameters;
    }

    public Object getResponse() {
        return response;
    }

    public void setResponse(Object response, Status status) {
        this.response = response;
        this.status = status;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "Message{" +
                "protocolName='" + protocolName + '\'' +
                ", id=" + id +
                ", methodName='" + methodName + '\'' +
                ", parameterClasses=" + Arrays.toString(parameterClasses) +
                ", parameters=" + Arrays.toString(parameters) +
                ", status=" + status +
                ", response=" + response +
                ", responseClass=" + responseClass +
                ", e=" + e +
                '}';
    }
}
