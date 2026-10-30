package com.hr.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.mcp.McpToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 地图数据刷新接口：前端历史会话中点击"打开地图"按钮时，
 * 用消息标记里保存的参数重新调用高德工具，返回最新的 [AMAP] 标记数据。
 *
 * <p>工具调用走 MCP 协议（MCP Server adk-tools 上的 planRoute / searchPlace），
 * 进程内不再直连高德 API。
 */
@RestController
@RequestMapping("/api/amap")
public class AmapController {

    private static final Logger log = LoggerFactory.getLogger(AmapController.class);
    private static final Pattern AMAP_MARKER = Pattern.compile("\\[AMAP](.*?)\\[/AMAP]", Pattern.DOTALL);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpToolGateway gateway;

    public AmapController(McpToolGateway gateway) {
        this.gateway = gateway;
    }

    public record RefreshReq(String type, String origin, String destination, String mode,
                             String city, String keywords) {}

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody RefreshReq req) {
        boolean route = "route".equals(req.type());
        String toolName = route ? "planRoute" : "searchPlace";

        // 请求字段可能为 null，Map.of 遇 null 会 NPE，故用 HashMap
        Map<String, Object> args = new HashMap<>();
        if (route) {
            args.put("origin", req.origin());
            args.put("destination", req.destination());
            args.put("city", req.city());
            args.put("mode", req.mode());
        } else {
            args.put("keywords", req.keywords());
            args.put("city", req.city());
        }

        Optional<ToolCallback> tool = gateway.findTool("adk-tools", toolName);
        if (tool.isEmpty()) {
            return Map.of("ok", false, "amap", List.of(),
                    "text", "地图服务不可用：MCP Server adk-tools 未接入或缺少工具 " + toolName
                            + "，请先启动 mcp-server（端口 20811）后重启后端");
        }

        String text;
        try {
            text = tool.get().call(MAPPER.writeValueAsString(args));
        } catch (Exception e) {
            log.warn("MCP 工具调用失败 tool={}", toolName, e);
            return Map.of("ok", false, "amap", List.of(), "text", "地图数据刷新失败：" + e.getMessage());
        }

        List<String> specs = new ArrayList<>();
        Matcher m = AMAP_MARKER.matcher(text);
        while (m.find()) {
            specs.add(m.group(1));
        }
        String plain = text.replaceAll("(?s)\\[AMAP].*?\\[/AMAP]", "").trim();
        return Map.of("ok", true, "amap", specs, "text", plain);
    }
}
