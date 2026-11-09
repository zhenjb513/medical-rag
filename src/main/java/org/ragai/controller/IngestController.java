package org.ragai.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.ragai.service.PdfUploadProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/ingest")
@Validated
public class IngestController {

    private final PdfUploadProducer producer;

    @Autowired
    public IngestController(PdfUploadProducer producer) {
        this.producer = producer;
    }

    @PostMapping("/pdf")
    public Map<String, Object> uploadPdf(@RequestParam @NotBlank(message = "filePath 不能为空") String filePath) {
        Map<String, Object> result = new LinkedHashMap<>();
        File f = new File(filePath);
        if (!f.exists()) {
            result.put("code", 404);
            result.put("message", "文件不存在: " + filePath);
            return result;
        }
        if (!filePath.toLowerCase().endsWith(".pdf")) {
            result.put("code", 400);
            result.put("message", "只支持 .pdf 文件");
            return result;
        }
        String taskId = producer.submit(filePath, f.getName());
        result.put("code", 0);
        result.put("taskId", taskId);
        result.put("message", "任务已提交，后台异步入库中");
        result.put("status", "PENDING");
        result.put("progress", 0);
        return result;
    }

    @GetMapping("/status/{taskId}")
    public Map<String, Object> status(@PathVariable @NotBlank String taskId) {
        Map<String, Object> result = new LinkedHashMap<>();
        PdfUploadProducer.TaskStatus s = producer.getStatus(taskId);
        if (s == null) {
            result.put("code", 404);
            result.put("message", "taskId 不存在或已过期");
            return result;
        }
        result.put("code", 0);
        result.put("taskId", taskId);
        result.put("state", s.state);
        result.put("fileName", s.fileName);
        result.put("progress", s.progress);
        result.put("errorMsg", s.errorMsg);
        result.put("finishedAt", s.finishedAt);
        return result;
    }
}