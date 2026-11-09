package org.ragai.service;

import com.rabbitmq.client.Channel;
import org.ragai.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class PdfIngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(PdfIngestConsumer.class);

    private final RagInitializer ragInitializer;
    private final Bm25IndexService bm25IndexService;
    private final PdfUploadProducer producer;

    @Autowired
    public PdfIngestConsumer(RagInitializer ragInitializer,
                             Bm25IndexService bm25IndexService,
                             PdfUploadProducer producer) {
        this.ragInitializer = ragInitializer;
        this.bm25IndexService = bm25IndexService;
        this.producer = producer;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void onMessage(PdfUploadProducer.PdfUploadMessage msg, Channel channel, Message raw) {
        long tag = raw.getMessageProperties().getDeliveryTag();
        String taskId = msg.taskId();
        log.info("[RabbitMQ] 消费 PDF 入库任务: taskId={} file={}", taskId, msg.fileName());
        try {
            producer.updateStatus(taskId, "PROCESSING", 10, null);

            File pdf = new File(msg.filePath());
            if (!pdf.exists()) {
                throw new IllegalArgumentException("PDF 文件不存在: " + msg.filePath());
            }

            producer.updateStatus(taskId, "PROCESSING", 30, null);
            ragInitializer.init();

            producer.updateStatus(taskId, "PROCESSING", 70, null);
            bm25IndexService.rebuildAll();

            producer.updateStatus(taskId, "DONE", 100, null);
            channel.basicAck(tag, false);
            log.info("[RabbitMQ] PDF 入库完成: taskId={}", taskId);
        } catch (Exception e) {
            log.error("[RabbitMQ] PDF 入库失败: taskId={} err={}", taskId, e.getMessage(), e);
            producer.updateStatus(taskId, "FAILED", 0, e.getMessage());
            try {
                channel.basicNack(tag, false, false);
            } catch (Exception ackErr) {
                log.error("[RabbitMQ] nack 失败: {}", ackErr.getMessage());
            }
        }
    }

    @RabbitListener(queues = RabbitMQConfig.DLX_QUEUE)
    public void onDlxMessage(PdfUploadProducer.PdfUploadMessage msg) {
        log.warn("[RabbitMQ] 死信队列收到消息: taskId={} file={}", msg.taskId(), msg.fileName());
        producer.updateStatus(msg.taskId(), "DEAD", 0, "消费重试 3 次后仍失败，已进死信队列");
    }
}