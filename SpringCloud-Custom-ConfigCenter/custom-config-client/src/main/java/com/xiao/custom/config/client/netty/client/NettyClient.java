package com.xiao.custom.config.client.netty.client;

import com.xiao.custom.config.client.configuration.ConfigClientProperties;
import com.xiao.custom.config.client.netty.dto.CommandEnum;
import com.xiao.custom.config.client.netty.dto.Message;
import com.xiao.custom.config.client.netty.factory.CoderFactory;
import com.xiao.custom.config.client.netty.factory.NamedThreadFactory;
import com.xiao.custom.config.client.netty.handler.ServiceHandler;
import com.xiao.custom.config.client.netty.util.RemotingUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;

import javax.annotation.Resource;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [简要描述]: netty客户端
 * [详细描述]:
 *
 * @author llxiao
 * @version 1.0, 2019/3/30 16:42
 * @since JDK 1.8
 */
@Configuration
@Slf4j
public class NettyClient implements ApplicationListener<ContextRefreshedEvent>, DisposableBean
{
    private String nettyServerHost = "localhost";
    private int nettyServerPort = 8999;

    private AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 重连检测任务
     */
    private ScheduledExecutorService reConnectExecutor;

    @Resource
    private ConfigClientProperties configClientProperties;

    private Bootstrap boot;

    private Channel channel;

    @Override
    public void destroy() throws Exception
    {
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent)
    {
        log.info(">>> 配置中心服务地址:{}", configClientProperties.getUri());
        nettyServerHost = parseHost(configClientProperties.getUri());
        nettyServerPort = configClientProperties.getNettyPort();
        init();
        connect();
        //2分钟检测一次连接情况
        reConnectExecutor.scheduleAtFixedRate((Runnable) () -> check(), 1, 2, TimeUnit.MINUTES);

    }

    /**
     * [简要描述]:发送消息<br/>
     * [详细描述]:<br/>
     *
     * @param message :
     * llxiao  2019/4/1 - 11:38
     **/
    public void pushMessage(Message message)
    {
        if (null != message && started.get())
        {
            channel.writeAndFlush(message);
        }
    }

    private void login()
    {
        Message message = new Message();
        message.setCommand(CommandEnum.LOGIN.getStatus());
        message.setServerPort(configClientProperties.getServerPort());
        message.setHostIp(RemotingUtil.getHost(configClientProperties.getUri()));
        pushMessage(message);
    }

    /**
     * 获取服务端IP地址
     *
     * @param uri
     * @return
     */
    private String parseHost(String uri)
    {
        URL url = null;
        try
        {
            url = new URL(uri);
        }
        catch (MalformedURLException e)
        {
            log.error("解析地址错误!");
        }
        return url.getHost();
    }

    private void init()
    {
        reConnectExecutor = new ScheduledThreadPoolExecutor(1, NamedThreadFactory
                .create("Netty client reconnect-", true));
        EventLoopGroup group = new NioEventLoopGroup();

        boot = new Bootstrap();
        boot.group(group).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true);
        boot.handler(new ChannelInitializer<Channel>()
        {
            @Override
            protected void initChannel(Channel channel) throws Exception
            {
                ChannelPipeline pipeline = channel.pipeline();
                //监听写动作，4S后无写动作，触发userEventTriggered发起心跳
                pipeline.addLast(new IdleStateHandler(0, 4, 0));

                //先解码 后编码
                pipeline.addLast(CoderFactory.newDecoder());
                pipeline.addLast(CoderFactory.newEncoder());

                //业务处理
                pipeline.addLast(new ServiceHandler(started));
            }
        });
    }

    private void connect()
    {
        log.info(">>> 客户端建立netty 连接，服务端-IP:{},Port:{}....", nettyServerHost, nettyServerPort);
        try
        {
            final ChannelFuture sync = boot.connect(nettyServerHost, nettyServerPort).sync();
            channel = sync.channel();
            started.set(sync.isSuccess());
            login();
        }
        catch (Exception e)
        {
            log.error(">>> 连接服务端异常，等待重连....", e);
        }
    }

    private void check()
    {

        if (log.isDebugEnabled())
        {
            log.debug(">>> 执行检测任务.....");
        }

        if (!started.get())
        {
            log.info(">>> 执行客户端重连操作...");
            connect();
        }
    }

    /**
     * 设置状态
     *
     * @param started
     */
    public void setStarted(boolean started)
    {
        this.started.set(started);
    }
}
