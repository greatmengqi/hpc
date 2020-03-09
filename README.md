## hadoop 1.0.0 RPC框架仿写

### hadoop RPC框架简介
hadoop RPC框架主要包括两个组件，服务端和客户端。服务端包括一个listener线程，一组Reader线程，一组Handle线程和一个Responder线程。listener线程包含一个SocketChannel和一个Selector，只负责监听客户端连接。每个Reader线程包含一个独有的Selector,每当Listener线程监听到客户端连接后就取一个Reader并向其注册一个read事件。Reader负责读取客户端请求，并将其包装成一个Call放入阻塞队列calls。Handle负责处理阻塞队列的call处理。然后处理的结果先自己发送，如果发送的完就结束此次请求，如果没法发送完就交给Responder去处理。Responder也是一个拥有Selector的线程，只负责处理写时间。

### 个人实现
* 保留服务端基本架构
* 移除Responder，让Handle线程之间发生所以数据
* 将客户端的Socket连接替换为SocketChanel，使用buffer和服务器传递数据
* 移除原框架自带的安全验证等和hadoop集群有关的功能组件
* 利用开源的protostuff框架替换原有的序列化和反序列化工具

### 待改进计划
* 使用Log4j作为日志，便于调试和日志配置

### bug

* bug1 多次向服务器发送请求会服务器会阻塞，貌似是发生了死锁 
bug原因，客户端一次执行多个任务的时候发生，第二次不应该再发起连接，否则就会向服务端发起两次请求，这样就会把一个连接交给两个Reader处理
解决方向：
研究源码服务端是如何处理来自同一个客户端的两个连接的
研究源码客户端是如何处理连续的连接的

解决方法：
源码中将Client的client对象进行缓存，相当于将连接一起缓存

* bug2 每次客户端断开连接，服务器就得到一个读事件
