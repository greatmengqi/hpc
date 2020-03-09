package cmq.rpc;

import cmq.util.ByteUtil;
import cmq.util.SerializingUtil;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @ProjectName: hpc
 * @Package: cmq.socket
 * @ClassName: server
 * @Author: chenmengqi
 * @Description:
 * @Date: 2020/3/6 11:03 上午
 * @Version: 1.0
 */


public class Server implements Serializable {
    private int port;
    private String address;
    private Listener listener;
    private Handler[] handlers;
    private int handlerCount;
    private LinkedBlockingQueue<Call> callQueue;
    private LinkedBlockingQueue<Call> finishedCall;
    private int maxQueueSize;
    private Boolean isRunning = true;
    private Object instance;  // handle执行任务的时候要用其调用函数


    public Server(Object instance, int port, String address) throws IOException {
        this.instance = instance;
        this.port = port;
        this.address = address;
        this.listener = new Listener();
        this.handlerCount = 10;
        this.maxQueueSize = 100;
        this.callQueue = new LinkedBlockingQueue<Call>(maxQueueSize);
        this.finishedCall = new LinkedBlockingQueue<Call>(maxQueueSize);
    }


    public void start() {
        this.listener.start();
        this.handlers = new Handler[handlerCount];
        for (int i = 0; i < handlerCount; i++) {
            handlers[i] = new Handler(i);
            handlers[i].start();
        }
        // responder
    }

    public Object call(Call call) throws IOException {
        try {

            // 1 拿到协议
            Class<?> protocol = call.connection.protocol;

            // 2. 依靠协议执行方法
            Method method = protocol.getMethod(call.message.getMethodName(), call.message.getParameterClasses());

            method.setAccessible(true);

            Object value = method.invoke(instance, call.message.getParameters());

            return value;

        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof IOException) {
                throw (IOException) target;
            } else {
                IOException ioe = new IOException(target.toString());
                ioe.setStackTrace(target.getStackTrace());
                throw ioe;
            }
        } catch (Throwable e) {
            if (!(e instanceof IOException)) {
                System.out.println("Unexpected throwable object " + e);
            }
            IOException ioe = new IOException(e.toString());
            ioe.setStackTrace(e.getStackTrace());
            throw ioe;
        }
    }

    public void stop() {
        isRunning = false;
    }

    // 服务器字典的 Call
    private class Call {
        Message message;
        Connection connection;

        public Call(Message message, Connection connection) {
            this.message = message;
            this.connection = connection;
        }

        public void setmessgeResponse(Object object, Status status) {
            this.message.setResponse(object, status);
        }

        @Override
        public String toString() {
            return "Call{" +
                    "message=" + message +
                    ", connection=" + connection +
                    '}';
        }
    }

    // 服务器自带的 Connection
    private class Connection {
        private SocketChannel channel;
        private Socket socket;
        // Cache the remote host & port info so that even if the socket is
        // disconnected, we can say where it used to connect to.
        private String hostAddress;
        private int remotePort;
        private InetAddress addr;
        private Class<?> protocol;

        public Connection(SocketChannel channel) {
            this.channel = channel;
            this.socket = channel.socket();
            this.remotePort = socket.getPort();
            this.addr = socket.getInetAddress();
            try {
                this.hostAddress = addr.getHostAddress();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public Class<?> getProtocol() {
            return protocol;
        }

        public void setProtocol(Class<?> protocol) {
            this.protocol = protocol;
        }

        private synchronized void close() throws IOException {

            if (!channel.isOpen())
                return;
            try {
                socket.shutdownOutput();
            } catch (Exception e) {
            }
            if (channel.isOpen()) {
                try {
                    channel.close();
                } catch (Exception e) {
                }
            }
            try {
                socket.close();
            } catch (Exception e) {
            }
        }

    }

    private class Listener extends Thread {

        private ServerSocketChannel serverSocket;
        private Selector acceptSelector;

        // 下面两个元素可以直接使用父类的元素
        //        private int port;
        //        private String address;

        private InetSocketAddress socketAddress;
        private Reader[] readers = new Reader[10];
        private int currentReader = 0;
        private int readThreads = 10;

        private class Reader implements Runnable {
            private Selector readerSelector;
            private boolean adding;
            private int index;


            public Reader(Selector selector, int index) {
                this.readerSelector = selector;
                this.index = index;
            }

            @Override
            public void run() {
                // todo 加锁之后这么麻烦，还要想办法让出锁才能添加channel，为啥不一开始就不加锁
                synchronized (this) {
                    while (isRunning) {
                        try {
                            // selector 一开始创建的时候并没有注册任何channel,所以每次执行到这里是必定要阻塞的，必须唤醒才能继续执行
                            // 正常来件，不唤醒也能继续执行，但是现在外面加锁了，不唤醒无法执行到下面去让出锁，就是死锁状态
                            readerSelector.select();

                            // 下面是为了让出锁，这样channel才能添加进来，可以主动唤醒，不然时间到了自己也会醒
                            while (adding) {
                                this.wait(1000);
                            }

                            SelectionKey key = null;
                            Iterator<SelectionKey> iterator = readerSelector.selectedKeys().iterator();
                            while (iterator.hasNext()) {
                                key = iterator.next();
                                iterator.remove();

                                if (key.isValid()) {
                                    if (key.isReadable()) {
                                        doRead(key);
                                    }
                                }
                                key = null;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }


            /**
             * reader的run方法是被锁定的，当adding 为 true的时候会调用 wait()让其让出锁
             * 然后下面的registerChannel()方法和finishAdd()方法才能执行，因为她是个同步方法。这个方法执行完调用notify()，run方法才有可能重新获得锁
             */
            public void startAdd() {
                this.adding = true;
                readerSelector.wakeup(); // 如果没有事件，selector会被阻塞，所以要先唤醒他让程序执行到wait()别的进程才能给本进程注册channel
            }

            /**
             * 把这个方法加锁，如果得不到锁就不能给selector注册事件
             * 只有adding为true的时候，run线程阻塞，才能给run线程注册channel
             *
             * @param channel
             * @return
             * @throws IOException
             */
            public synchronized SelectionKey registerChannel(SocketChannel channel)
                    throws IOException {
                return channel.register(readerSelector, SelectionKey.OP_READ);
            }

            public synchronized void finishAdd() {
                this.adding = false;
                this.notify();
            }
        }


        public Listener() throws IOException {
            this.socketAddress = new InetSocketAddress(address, port);
            this.serverSocket = ServerSocketChannel.open();

            // todo 需要对消息进行验证
            this.serverSocket.bind(socketAddress);
            this.serverSocket.configureBlocking(false);
            this.acceptSelector = Selector.open();

            // listener只接收连接消息
            this.serverSocket.register(acceptSelector, SelectionKey.OP_ACCEPT);

            // 线程池一经启动就常驻内存，除非用shutdown停止，拥有线程池的后台线程不会停止
            readers = new Reader[readThreads];
            ExecutorService executorService = Executors.newFixedThreadPool(readThreads);

            for (int i = 0; i < readThreads; i++) {
                Selector readSelector = Selector.open();
                Reader reader = new Reader(readSelector, i);
                readers[i] = reader;
                executorService.execute(reader);
            }

            // 置为后台线程
            this.setDaemon(true);
        }


        @Override
        public void run() {
            while (isRunning) {
                try {
                    acceptSelector.select();
                    Iterator<SelectionKey> iterator = acceptSelector.selectedKeys().iterator();

                    SelectionKey key = null;
                    while (iterator.hasNext()) {
                        key = iterator.next();
                        // 这里虽然remove掉了,但是如果不做处理把通道的内容读出来，下次还是会存在
                        iterator.remove();
                        if (key.isValid()) {
                            if (key.isAcceptable()) {
                                doAccept(key);
                            }
                        }
                        key = null;
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }


        private void doAccept(SelectionKey key) throws IOException {

            // 1 先通过key把channel拿到
            ServerSocketChannel server = (ServerSocketChannel) key.channel();
            SocketChannel channel;

            /* 为什么要循环呢？
             * 如果拿不到就跳出循环了，但是这个key仍然有效，下次遍历keys的时候再处理
             * 如果拿到了就直接交给reader了
             * */

            while ((channel = server.accept()) != null) {
                channel.configureBlocking(false);
                // 2 循环取一个reader
                Reader reader = getReader();

                /*
                 * 这里需要三步：
                 * 第一步，唤醒reader，因为reader的selector为空的时候会阻塞
                 * 第二步，让reader走到wait让出锁，因为registerChannel是同步方法要和reader的run方法抢锁
                 * 第三步，注册，并让出锁
                 */

                reader.startAdd();

                SelectionKey readerKey = reader.registerChannel(channel);

                // 绑定一个连接，便于后面读取数据
                Connection connection = new Connection(channel);
                readerKey.attach(connection);

                reader.finishAdd();
            }
        }


        /**
         * 这个方法是交给reader使用的,其作用是将客户端发来的数据组装成一个call，放到共享队列，供handle计算
         *
         * @param key
         */
        private void doRead(SelectionKey key) {

            Connection connection = (Connection) key.attachment();
            SocketChannel socketChannel = (SocketChannel) key.channel();


            try {
                // 先读取对象的长度
                ByteBuffer lenBuffer = ByteBuffer.allocate(2);
                socketChannel.read(lenBuffer);
                lenBuffer.flip();
                int dateLen = ByteUtil.toInt(lenBuffer.array());

                // 然后读取内容
                ByteBuffer data = ByteBuffer.allocate(dateLen);
                socketChannel.read(data);
                data.flip();
                Message message = SerializingUtil.deserialize(data.array(), Message.class);


                Class<?> protocolClass = Class.forName(message.getProtocolName());

                connection.setProtocol(protocolClass);

                Call call = new Call(message, connection);
                callQueue.put(call);

            } catch (Exception e) {
                //   e.printStackTrace();
                // 当客户端断开连接后，仍然会发一个读请求，但是已经无法读到数据，在捕获到异常的时候要把key取消
                key.cancel();
            }
        }


        /**
         * 循环使用Reader
         *
         * @return
         */
        private Reader getReader() {
            currentReader = (currentReader + 1) % readers.length;
            return readers[currentReader];
        }
    }

    private class Handler extends Thread {
        public Handler(int instanceNumber) {
            this.setDaemon(true);
            this.setName("IPC Server handler " + instanceNumber + " on " + port);
        }

        @Override
        public void run() {
            while (isRunning) {
                try {
                    final Call call = callQueue.take(); // pop the queue; maybe blocked here

                    Exception error = null;
                    Object value = null;

                    try {
                        // 接下来该把Call拿给真正的方法去执行
                        value = call(call);
                    } catch (IOException e) {
                        error = e;
                    }

                    call.setmessgeResponse(value, error == null ? Status.SUCCESS : Status.ERROR);

                    System.out.println(call);
                    // 直接发送
                    SocketChannel socketChannel = call.connection.channel;


                    // 防止数据交叉发送引起数据错位，所以如果使用通道发送数据就要上锁
                    synchronized (socketChannel) {
                        try {
                            byte[] messageArr = SerializingUtil.serialize(call.message);

                            // 先把待发送的数据的长度转为字节数组，整形数据占两个字节，够用了
                            byte[] lenByte = ByteUtil.toByteArray(messageArr.length, 2);
                            ByteBuffer lenBuffer = ByteBuffer.wrap(lenByte);
                            socketChannel.write(lenBuffer);

                            // 然后发送真实数据
                            ByteBuffer data = ByteBuffer.wrap(messageArr);
                            socketChannel.write(data);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                    }

                    // 把执行的结果放到队列交给response去处理
                    // finishedCall.put(call);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
