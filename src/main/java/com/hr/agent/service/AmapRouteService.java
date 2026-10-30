package com.hr.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.mcp.McpToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 高德路线规划服务：规划驾车路线，结果以 JSON 存入 biz_delivery.route_json。
 *
 * <p>实际调用走 MCP 协议——MCP Server（adk-tools）上的 driveRouteJson 工具，
 * 进程内不再直连高德 API。本类只保留对 DeliveryService 的适配：
 * 签名与"失败返回 null"契约不变。
 */
@Component
public class AmapRouteService {

    private static final Logger log = LoggerFactory.getLogger(AmapRouteService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpToolGateway gateway;

    public AmapRouteService(McpToolGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 规划驾车路线
     * @param fromLng 起点经度
     * @param fromLat 起点纬度
     * @param toLng 终点经度
     * @param toLat 终点纬度
     * @return 路线 JSON 字符串，失败返回 null
     */
    public String planRoute(double fromLng, double fromLat, double toLng, double toLat) {
        Optional<ToolCallback> tool = gateway.findTool("adk-tools", "driveRouteJson");
        if (tool.isEmpty()) {
            log.warn("MCP Server adk-tools 未接入或缺少 driveRouteJson 工具，跳过路线规划"
                    + "（需启动 mcp-server，端口 20811）");
            return null;
        }
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("originLng", fromLng);
            args.put("originLat", fromLat);
            args.put("destLng", toLng);
            args.put("destLat", toLat);
            String result = tool.get().call(MAPPER.writeValueAsString(args));
            // MCP 文本结果不能为 null：服务端失败返回空串，按契约映射为 null
            return (result == null || result.isBlank()) ? null : result;
        } catch (Exception e) {
            log.error("高德路线规划异常（MCP 调用失败）", e);
            return null;
        }
    }
}
