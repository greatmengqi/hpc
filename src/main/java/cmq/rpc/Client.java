package cmq.rpc;

import cmq.util.ByteUtil;
import cmq.util.SerializingUtil;
import org.apache.log4j.Logger;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.rmi.RemoteException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端要使用代理模式，把用户发送的方法请求解析成一个个Message
 *
 * @ProjectName: hpc
 * @Package: cmq.socket
 * @ClassName: Client
 * @Author: chenmengqi
 * @Description:
 * @Date: 2020/3/7 9:13 上午
 * @Version: 1.0
 */
public class Client {

    private Hashtable<ConnectionId, Connection> connections = new Hashtable<ConnectionId, Connection>();

    private AtomicBoolean running = new AtomicBoolean(true); // if client runs
    private int counter;
    private SocketFactory socketFactory;           // how to create sockets
    Logger logger = Logger.getLogger(Client.class);


    private Connection getConnection(ConnectionId remoteId,
                                     Call call)
            throws IOException, InterruptedException {
        if (!running.get()) {
            // the client is stopped
            throw new IOException("The client is stopped");
        }
        Connection connection;
        /* we could avoid this allocation for each RPC by having a
         * connectionsId object and with set() method. We need to manage the
         * refs for keys in HashMap properly. For now its ok.
         */
        Boolean addFlag = null;
        do {
            synchronized (connections) {
                connection = connections.get(remoteId);
                if (connection == null) {
                    connection = new Connection(remoteId);
                    connections.put(remoteId, connection);
                }
            }

            addFlag = connection.addCall(call);

        } while (!addFlag);

        //we don't invoke the method below inside "synchronized (connections)"
        //block above. The reason for that is if the server happens to be slow,
        //it will take longer to establish a connection and that will slow the
        //entire system down.
        logger.debug("准备建立连接");
        connection.setupIOstreams();
        return connection;
    }


    public Object call(String methodName, Object[] param, Class[] parameterClasses, ConnectionId remoteId, Class<?> protocol)
            throws InterruptedException, IOException {


        Message message = new Message(methodName, parameterClasses, param, protocol.getName());
        Call call = new Call(message);

        Connection connection = getConnection(remoteId, call); // 将call和connecttion绑定，并把connection放到hashMap里
        logger.debug("准备发送数据");
        connection.sendParam(call);                 // send the parameter

        boolean interrupted = false;
        synchronized (call) {
            while (!call.done) {

                try {
                    call.wait();             // call被锁定后，call的值无法更新，所以在这里阻塞，释放call的锁
                } catch (InterruptedException ie) {
                    // save the fact that we were interrupted
                    interrupted = true;
                }
            }

            if (interrupted) {
                // set the interrupt flag now that we are done waiting
                Thread.currentThread().interrupt();
            }

            if (call.error != null) {
                if (call.error instanceof RemoteException) {
                    call.error.fillInStackTrace();
                    throw call.error;
                } else {
                    throw wrapException(connection.getRemoteAddress(), call.error);
                }
            } else {
                return call.value;
            }
        }
    }

    private IOException wrapException(InetSocketAddress addr,
                                      IOException exception) {
        if (exception instanceof ConnectException) {
            //connection refused; include the host:port in the error
            return (ConnectException) new ConnectException(
                    "Call to " + addr + " failed on connection exception: " + exception)
                    .initCause(exception);
        } else if (exception instanceof SocketTimeoutException) {
            return (SocketTimeoutException) new SocketTimeoutException(
                    "Call to " + addr + " failed on socket timeout exception: "
                            + exception).initCause(exception);
        } else {
            return (IOException) new IOException(
                    "Call to " + addr + " failed on local exception: " + exception)
                    .initCause(exception);

        }
    }


    /**
     * 每一个Connection对应一个连接的一个协议，一个连接建立后就可以反复通信，后续通过call里的协议名称和远程地址
     * 就可以拿到对应的连接，将数据发送出去，每个连接都维护一个call队列，该队列的所有的call都属于同一个远程地址的同一个协议
     * connection属于一过性线程，添加Call的时候启动，得到结果后关闭，下次添加任务再开启
     */
    /**
     * Thread that reads responses and notifies callers.  Each connection owns a
     * socket connected to a remote address.  Calls are multiplexed through this
     * socket: responses may be delivered out of order.
     */
    private class Connection extends Thread {
        private InetSocketAddress server;             // server ip:port
        private final ConnectionId remoteId;
        private Selector selector;
        private SocketChannel socketChannel;
        private int retryTime = 0;


        // 可能一个客户端的多个线程同时使用一个连接，发送多个任务，但是根据任务处理的速度不同，有的先返回，有的后返回
        // 所以要区分任务是哪个，从而把结果返回给相应的客户端调用线程
        private final Hashtable<Integer, Call> calls = new Hashtable<Integer, Call>();


        // 设置一个定时器，时间到后主动关闭连接。
        private AtomicBoolean shouldCloseConnection = new AtomicBoolean();
        private IOException closeException; // close reason

        public Connection(ConnectionId remoteId) throws IOException {
            this.remoteId = remoteId;
            this.server = remoteId.getAddress();

            if (server.isUnresolved()) {
                throw new UnknownHostException("unknown host: " +
                        remoteId.getAddress().getHostName());
            }

            Class<?> protocol = remoteId.getProtocol();


            this.setDaemon(true);
        }

        /**
         * Add a call to this connection's call queue and notify
         * a listener; synchronized.
         * Returns false if called during shutdown.
         *
         * @param call to add
         * @return true if the call was added.
         */
        private synchronized boolean addCall(Call call) {
            if (shouldCloseConnection.get())
                return false;
            logger.debug("添加call进入calls");
            calls.put(call.id, call);
            logger.debug("添加call成功");
            notify();
            return true;
        }


        /**
         * 初始化IO流，每次客户端取用连接的时候都要调用
         * 如果连接已经建立，直接返回
         * 否则重新建立连接
         */
        private synchronized void setupIOstreams() throws InterruptedException, ClosedChannelException {
            if (shouldCloseConnection.get()) {
                return;
            }

            if (socketChannel != null) {
                return;
            }

            try {

                while (true) {
                    // 每个Connection在初始化的时候拥有远程的地址和端口号
                    logger.debug("准备建立连接");
                    setupConnection();// 建立连接
                    logger.debug("建立连接");

                    //  建立连接后就可以启动本线程，监听有没有call
                    //  需要注意的是上面如果socketchannel如果不为空就返回了，所以连接线程是一次启动永久使用
                    //  所以在run方法里，如果socket关闭了，线程就要结束，同时要把socket置为null,不然就会启动两个线程同时运行
                    start();
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
                markClosed(e);
                close();
            }
        }

        private void closeConnection() {
            // close the current connection
            try {
                socketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            // set socket to null so that the next call to setupIOstreams
            // can start the process of connect all over again.
            socketChannel = null;
        }


        public InetSocketAddress getRemoteAddress() {
            return server;
        }


        /**
         * 建立远程连接，保证此句执行完后，本连接的socket不能为空
         *
         * @throws IOException
         */
        private synchronized void setupConnection() throws IOException, InterruptedException {
            while (true) {
                try {

                    InetSocketAddress address = this.remoteId.address;
                    this.socketChannel = SocketChannel.open(address);
                    this.socketChannel.configureBlocking(false);

                    return;
                } catch (Exception e) {
                    retryTime++;
                    wait(1000);
                    if (retryTime > 15) {
                        throw e;
                    }
                    e.printStackTrace();
                }
            }
        }


        private synchronized boolean waitForWork() {
            // 如果队列为空就阻塞一下，等待任务添加，但是始终不让他关闭
            if (calls.isEmpty() && !shouldCloseConnection.get() && running.get()) {
                long timeout = 1000;
                if (timeout > 0) {
                    try {
                        wait(timeout);
                    } catch (InterruptedException e) {
                    }
                }
            }

            if (!calls.isEmpty() && !shouldCloseConnection.get() && running.get()) {
                return true;
            } else if (shouldCloseConnection.get()) {
                return false;
            } else if (calls.isEmpty()) { // idle connection closed or stopped
                markClosed(null);
                return false;
            } else { // get stopped but there are still pending requests
                markClosed((IOException) new IOException().initCause(
                        new InterruptedException()));
                return false;
            }
        }


        public void run() {

            //如果连接没有置为关闭状态，就继续接受，否则就打开
            // 如果接收数据出现异常就要关闭线程
            while (waitForWork()) {
                // 没有请求就不会有数据
                receiveResponse();
            }
            close();

        }


        /**
         * 这个方法是客户端主动调用，也就是客户端只管发送，然后连接线程负责收
         * 只有连接建立之后才能发送，所以每次call入队后先判断有没有现成的连接，如果有就取出来，没有就重新建立
         */
        public void sendParam(Call call) {
            if (shouldCloseConnection.get()) {
                return;
            }
            try {

                // 先将要发送的数据组成一个message
                byte[] messageArr = SerializingUtil.serialize(call.message);

                // 先把待发送的数据的长度转为字节数组，整形数据占两个字节，够用了
                byte[] lenByte = ByteUtil.toByteArray(messageArr.length, 2);
                ByteBuffer lenBuffer = ByteBuffer.wrap(lenByte);

                // 然后发送真实数据
                ByteBuffer data = ByteBuffer.wrap(messageArr);


                // 两次写入时间间隔要短
                logger.debug("发送数据...");
                socketChannel.write(lenBuffer);
                socketChannel.write(data);
                logger.debug("发送数据成功");
            } catch (IOException e) {
                markClosed(e);
            }
        }

        /* Receive a response.
         * Because only one receiver, so no synchronization on in.
         */
        private void receiveResponse() {
            if (shouldCloseConnection.get()) {
                return;
            }

            logger.debug("开始读取数据...");

            try {
                ByteBuffer lenBuffer = ByteBuffer.allocate(2);

                while (this.socketChannel.read(lenBuffer) <= 0) {

                }

                lenBuffer.flip();
                int dateLen = ByteUtil.toInt(lenBuffer.array());

                // 然后读取内容
                ByteBuffer data = ByteBuffer.allocate(dateLen);
                while (this.socketChannel.read(data) == 0) {
                    logger.debug("等待数据写入");
                }
                data.flip();
                Message message = SerializingUtil.deserialize(data.array(), Message.class);
                if (message != null) {
                    logger.debug("数据读取成功" + message);
                }
                // 拿到对应的Call
                Call call = calls.get(message.id);
                logger.debug("获取call..");

                if (message.status == Status.SUCCESS) {
                    // 客户端发送请求后，就一直判断call的状态，所以如果有返回值，就修改call的状态，这样用户就能拿到结果了
                    call.setValue(message.getResponse());
                    calls.remove(message.id);
                } else if (message.status == Status.ERROR) {
                    call.setException((IOException) message.getE());
                    calls.remove(message.id);
                }
            } catch (IOException e) {
                markClosed(e);
            }
        }

        private synchronized void markClosed(IOException e) {
            if (shouldCloseConnection.compareAndSet(false, true)) {
                closeException = e;
                notifyAll();
            }
        }

        /**
         * Close the connection.
         */
        private synchronized void close() {
            if (!shouldCloseConnection.get()) {
                return;
            }

            // release the resources
            // first thing to do;take the connection out of the connection list
            synchronized (connections) {
                if (connections.get(remoteId) == this) {
                    connections.remove(remoteId);
                }
            }

            // close the streams and therefore the socket
            closeConnection();

            // clean up all calls
            if (closeException == null) {
                if (!calls.isEmpty()) {
                    // clean up calls anyway
                    closeException = new IOException("Unexpected closed connection");
                    cleanupCalls();
                }
            } else {

                // cleanup calls
                cleanupCalls();
            }
        }

        /* Cleanup all calls and mark them as done */
        private void cleanupCalls() {
            Iterator<Map.Entry<Integer, Call>> itor = calls.entrySet().iterator();
            while (itor.hasNext()) {
                Call c = itor.next().getValue();
                c.setException(closeException); // local exception
                itor.remove();
            }
        }
    }

    /**
     * 作为hash表的键值，所以必须重写hashcode和equals
     */
    static class ConnectionId {
        InetSocketAddress address;
        Class<?> protocol;
        private static final int PRIME = 16777619;

        ConnectionId(InetSocketAddress address, Class<?> protocol) {
            this.protocol = protocol;
            this.address = address;
        }

        InetSocketAddress getAddress() {
            return address;
        }

        Class<?> getProtocol() {
            return protocol;
        }


        static ConnectionId getConnectionId(InetSocketAddress addr,
                                            Class<?> protocol) throws IOException {
            return new ConnectionId(addr, protocol);
        }

        static boolean isEqual(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof ConnectionId) {
                ConnectionId that = (ConnectionId) obj;
                return isEqual(this.address, that.address)
                        && isEqual(this.protocol, that.protocol);
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = PRIME * result + ((address == null) ? 0 : address.hashCode());
            result = PRIME * result + ((protocol == null) ? 0 : protocol.hashCode());
            return result;
        }
    }


    /**
     * 客户端独有的Call
     */

    private class Call {
        int id;                                       // call id
        Object value;                               // value, null if error
        Message message;
        IOException error;                            // exception, null if value
        boolean done;                                 // true when call is done

        protected Call(Message message) {
            this.message = message;
            synchronized (Client.this) {
                this.id = counter++;
                message.setId(this.id);
            }
        }

        /**
         * Indicate when the call is complete and the
         * value or error are available.  Notifies by default.
         */
        protected synchronized void callComplete() {
            this.done = true;
            notify();                                 // notify caller
        }

        /**
         * Set the exception when there is an error.
         * Notify the caller the call is done.
         *
         * @param error exception thrown by the call; either local or remote
         */
        public synchronized void setException(IOException error) {
            this.error = error;
            callComplete();
        }

        /**
         * Set the return value when there is no error.
         * Notify the caller the call is done.
         *
         * @param value return value of the call.
         */
        public synchronized void setValue(Object value) {
            this.value = value;
            callComplete();
        }
    }

}
