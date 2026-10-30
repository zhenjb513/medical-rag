package org.ragai.service;

import org.ragai.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PdfUploadProducer {

    private static final Logger log = LoggerFactory.getLogger(PdfUploadProducer.class);

    private final RabbitTemplate rabbitTemplate;
    private final Map<String, TaskStatus> statusMap = new ConcurrentHashMap<>();

    @Autowired
    public PdfUploadProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public String submit(String filePath, String fileName) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PdfUploadMessage msg = new PdfUploadMessage(taskId, filePath, fileName, System.currentTimeMillis());
        statusMap.put(taskId, new TaskStatus("PENDING", filePath, fileName, 0, null));
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, msg);
        log.info("[RabbitMQ] PDF 入库任务已提交: taskId={} file={}", taskId, fileName);
        return taskId;
    }

    public TaskStatus getStatus(String taskId) {
        return statusMap.get(taskId);
    }

    void updateStatus(String taskId, String state, int progress, String errorMsg) {
        statusMap.computeIfPresent(taskId, (id, s) -> {
            s.state = state;
            s.progress = progress;
            s.errorMsg = errorMsg;
            s.finishedAt = System.currentTimeMillis();
            return s;
        });
    }

    public record PdfUploadMessage(String taskId, String filePath, String fileName, long submittedAt) {}

    public static class TaskStatus {
        public String state;
        public String filePath;
        public String fileName;
        public int progress;
        public String errorMsg;
        public Long finishedAt;

        public TaskStatus(String state, String filePath, String fileName, int progress, String errorMsg) {
            this.state = state;
            this.filePath = filePath;
            this.fileName = fileName;
            this.progress = progress;
            this.errorMsg = errorMsg;
        }
    }
}