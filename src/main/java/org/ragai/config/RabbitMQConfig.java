package org.ragai.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "pdf.upload.exchange";
    public static final String QUEUE = "pdf.upload.queue";
    public static final String ROUTING_KEY = "pdf.upload";
    public static final String DLX_EXCHANGE = "pdf.upload.dlx.exchange";
    public static final String DLX_QUEUE = "pdf.upload.dlx.queue";
    public static final String DLX_ROUTING_KEY = "pdf.upload.dlx";

    @Bean
    public DirectExchange pdfUploadExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue pdfUploadQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY)
                .withArgument("x-message-ttl", 300_000)
                .build();
    }

    @Bean
    public Binding pdfUploadBinding() {
        return BindingBuilder.bind(pdfUploadQueue())
                .to(pdfUploadExchange())
                .with(ROUTING_KEY);
    }

    @Bean
    public DirectExchange pdfUploadDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue pdfUploadDlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding pdfUploadDlxBinding() {
        return BindingBuilder.bind(pdfUploadDlxQueue())
                .to(pdfUploadDlxExchange())
                .with(DLX_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                System.err.println("[RabbitMQ] 消息投递失败: " + cause);
            }
        });
        return template;
    }
}