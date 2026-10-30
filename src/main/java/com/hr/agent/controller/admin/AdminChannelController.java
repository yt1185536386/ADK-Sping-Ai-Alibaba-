package com.hr.agent.controller.admin;

import com.hr.agent.service.AdminChannelService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端：模型来源管理（api_key 渠道配置）。
 *
 * <p>入口在管理端界面的「模型管理 / 模型来源」，仅系统管理员可见。
 * 出入口不返回明文密钥，只返回 keyConfigured。
 */
@RestController
@RequestMapping("/api/admin/channels")
public class AdminChannelController {

    private final AdminChannelService service;

    public AdminChannelController(AdminChannelService service) {
        this.service = service;
    }

    /** 渠道列表（含停用渠道，脱敏） */
    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    /** 新增渠道 */
    @PostMapping
    public Map<String, Object> create(@RequestBody AdminChannelService.ChannelForm form) {
        service.save(form);
        return Map.of("ok", true);
    }

    /** 更新渠道（provider 以路径为准） */
    @PutMapping("/{provider}")
    public Map<String, Object> update(@PathVariable String provider,
                                       @RequestBody AdminChannelService.ChannelForm form) {
        service.save(new AdminChannelService.ChannelForm(
                provider, form.name(), form.protocol(), form.authType(), form.baseUrl(), form.models(),
                form.defaultModel(), form.embeddingModel(), form.vendor(), form.weight(),
                form.isDefault(), form.enabled(), form.source(), form.remark(), form.apiKey()));
        return Map.of("ok", true);
    }

    /** 删除渠道 */
    @DeleteMapping("/{provider}")
    public Map<String, Object> delete(@PathVariable String provider) {
        service.delete(provider);
        return Map.of("ok", true);
    }

    /** 连通性测试：真实调用一次该渠道 */
    @PostMapping("/{provider}/test")
    public Map<String, Object> test(@PathVariable String provider,
                                     @RequestParam(required = false) String model) {
        return service.test(provider, model);
    }

    /** 拉取渠道 /models 端点的模型列表（provider/baseUrl/apiKey 均可选，缺省回退到已存渠道配置） */
    @PostMapping("/models")
    public Map<String, Object> models(@RequestBody ModelQuery query) {
        return service.fetchModels(query.provider(), query.baseUrl(), query.apiKey());
    }

    public record ModelQuery(String provider, String baseUrl, String apiKey) {
    }
}
