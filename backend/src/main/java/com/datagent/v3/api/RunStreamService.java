package com.datagent.v3.api;

import com.datagent.v3.agent.*;
import com.datagent.v3.session.SessionStore;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 后台执行和网络写入使用独立任务；慢连接脱离后可读取数据库快照。 */
@Service
@RequiredArgsConstructor
public class RunStreamService {
    /** 后台任务在应用关闭时释放。 */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /** 业务运行入口及调度失败的事务清理入口。 */
    private final AgentRunner runner;
    private final SessionStore sessions;

    public SseEmitter start(String runId) {
        var connection = new Connection();
        try {
            workers.execute(connection::drain);
            workers.execute(() -> {
                try {
                    runner.execute(runId, connection::publish);
                } finally {
                    connection.endProducer();
                }
            });
        } catch (RejectedExecutionException unavailable) {
            connection.detach();
            sessions.fail(runId, "执行服务正在关闭");
            throw unavailable;
        }
        return connection.emitter;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    private static class Connection {
        private static final int CAPACITY = 256;
        /** 单条响应独占的有界缓冲，避免网络阻塞模型线程。 */
        private final BlockingQueue<Optional<RunEvent>> queue = new ArrayBlockingQueue<>(CAPACITY);
        /** 模型拥有独立超时，连接在终态、断开或溢出时关闭。 */
        private final SseEmitter emitter = new SseEmitter(0L);
        private boolean detached;

        Connection() {
            emitter.onCompletion(this::detach);
            emitter.onTimeout(this::detach);
            emitter.onError(ignored -> detach());
        }

        synchronized void publish(RunEvent event) {
            if (!detached && !queue.offer(Optional.of(event))) detach();
        }

        synchronized void detach() {
            detached = true;
            queue.clear();
            queue.offer(Optional.empty());
        }

        synchronized void endProducer() {
            // 正常终态本身结束消费；异常退出也要唤醒等待方。
            if (!detached && !queue.offer(Optional.empty())) detach();
        }

        void drain() {
            try {
                while (true) {
                    var next = queue.take();
                    if (next.isEmpty()) break;
                    var event = next.get();
                    emitter.send(SseEmitter.event().name(event.type()).id(Long.toString(event.seq())).data(event));
                    if (event.terminal()) break;
                }
                emitter.complete();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (IOException | IllegalStateException disconnected) {
                // Servlet 容器处理写失败，后台 Run 继续执行并持久化。
            } finally {
                detach();
            }
        }
    }
}
