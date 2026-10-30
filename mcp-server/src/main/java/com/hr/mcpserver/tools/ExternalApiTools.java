package com.hr.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hr.mcpserver.service.ExternalToolConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 外部 API 工具集：天气 / 高德地图（地点、路线、公交、定位）+ 配送驾车路线（结构化）。
 *
 * <p>由主应用 tools/CommonTools 迁入（MCP 化改造，2026-10）：输出契约逐字保留——
 * 文本内容、[AMAP]...[/AMAP] 标记格式（前端交互地图契约）、错误说明文案均不变。
 * 方法名即 MCP 工具名，主应用 CommonAgent 的 system prompt 逐字引用，不得改名。
 *
 * <p>所有外部 endpoint 与密钥都从 {@code external_tool} 表读取（经 {@link ExternalToolConfigService}），
 * 不在代码里硬编码 URL 或 key；表里没有配置或密钥缺失时，工具会返回可读说明让模型转告用户。
 */
@Component
public class ExternalApiTools {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final ExternalToolConfigService externalToolConfigService;

    public ExternalApiTools(ExternalToolConfigService externalToolConfigService) {
        this.externalToolConfigService = externalToolConfigService;
    }

    // ==================== 天气（wttr.in，免 key） ====================

    @Tool(description = "查询某个城市的实时天气。当用户询问某地当前天气、气温、湿度、风力时调用。city 传中文城市名，如\"北京\"、\"上海\"，不要传省份或国家。")
    public String queryWeather(
            @ToolParam(description = "中文城市名，如 北京、上海、长沙") String city) {
        Optional<ExternalToolConfigService.ExternalTool> toolOpt = externalToolConfigService.findTool("weather");
        if (toolOpt.isEmpty()) {
            return "天气查询工具未在 external_tool 表中配置（tool_key=weather），请联系管理员";
        }
        ExternalToolConfigService.ExternalTool tool = toolOpt.get();
        try {
            String url = tool.baseUrl() + "/" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "?format=j1&lang=zh";
            JsonNode root = MAPPER.readTree(get(url, tool.timeoutMs()));
            JsonNode cur = root.path("current_condition").path(0);

            String desc = "";
            for (JsonNode zh : cur.path("lang_zh")) {
                if (!zh.path("value").asText("").isBlank()) {
                    desc = zh.path("value").asText();
                    break;
                }
            }
            if (desc.isBlank()) {
                desc = cur.path("weatherDesc").path(0).path("value").asText("未知");
            }
            return String.format("%s当前天气：%s，气温 %s℃（体感 %s℃），湿度 %s%%，风速 %skm/h",
                    city, desc,
                    cur.path("temp_C").asText("?"),
                    cur.path("FeelsLikeC").asText("?"),
                    cur.path("humidity").asText("?"),
                    cur.path("windspeedKmph").asText("?"));
        } catch (Exception e) {
            log.warn("天气查询失败 city={}", city, e);
            return "天气查询出错：" + e.getMessage() + "，请向用户说明情况（可提示稍后重试或换个城市名）";
        }
    }

    // ==================== 高德公交（需 amap key） ====================

    @Tool(description = "查询某条公交车或地铁线路的途经站点、首末班时间和票价。line 传线路名如\"118路\"、\"地铁2号线\"，city 传中文城市名如\"长沙\"。注意：只查单条线路的站点信息，用户问\"从A到B怎么走\"时不要调用。")
    public String queryBusRoute(
            @ToolParam(description = "线路名，如 118路、地铁2号线") String line,
            @ToolParam(description = "中文城市名，如 长沙、北京") String city) {
        Optional<ExternalToolConfigService.ExternalTool> toolOpt = externalToolConfigService.findTool("amap");
        if (toolOpt.isEmpty() || !toolOpt.get().hasApiKey()) {
            return "公交查询功能未配置高德 key，请提示管理员到高德开放平台(lbs.amap.com)申请 Web 服务 key，"
                    + "并用主应用的 ExternalToolService.saveApiKey(\"amap\", key) 写入 external_tool 表";
        }
        ExternalToolConfigService.ExternalTool tool = toolOpt.get();
        String keyParam = tool.authKeyName() == null || tool.authKeyName().isBlank()
                ? "key" : tool.authKeyName();
        try {
            String url = tool.baseUrl() + "/v3/bus/linename"
                    + "?" + keyParam + "=" + URLEncoder.encode(tool.apiKey(), StandardCharsets.UTF_8)
                    + "&city=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "&keywords=" + URLEncoder.encode(line, StandardCharsets.UTF_8)
                    + "&extensions=all";
            JsonNode data = MAPPER.readTree(get(url, tool.timeoutMs()));
            if (!"1".equals(data.path("status").asText())) {
                return "公交查询失败：" + data.path("info").asText("未知错误") + "，请向用户说明";
            }
            JsonNode lines = data.path("buslines");
            if (!lines.isArray() || lines.isEmpty()) {
                return "未在" + city + "找到线路「" + line + "」，请确认线路名是否正确（如 118路）";
            }
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                JsonNode bl = lines.get(i);
                List<String> stops = new ArrayList<>();
                for (JsonNode s : bl.path("busstops")) {
                    stops.add(s.path("name").asText());
                }
                parts.add(String.format(
                        "线路：%s\n首末站：%s ↔ %s\n首末班：%s ~ %s\n票价：%s元\n途经站点：%s",
                        bl.path("name").asText(line),
                        bl.path("start_stop").asText("?"),
                        bl.path("end_stop").asText("?"),
                        fmtTime(bl.path("start_time").asText("")),
                        fmtTime(bl.path("end_time").asText("")),
                        bl.path("total_price").asText("未知"),
                        stops.isEmpty() ? "无站点数据" : String.join(" → ", stops)));
            }
            return String.join("\n\n", parts);
        } catch (Exception e) {
            log.warn("公交查询失败 line={} city={}", line, city, e);
            return "公交查询出错：" + e.getMessage() + "，请向用户说明情况";
        }
    }

    // ==================== 高德地点搜索与路线规划（需 amap key） ====================

    @Tool(description = "获取用户当前所在位置（基于IP定位，精确到城市级别）。当用户询问\"我在哪\"、\"当前位置\"、\"附近有什么\"，或路线规划时用户没有提供起点、要求从当前位置出发时必须调用本工具。返回省份、城市、行政区划代码和城市中心坐标。注意：定位精度为城市级，不是精确门牌位置。")
    public String getCurrentLocation() {
        Optional<ExternalToolConfigService.ExternalTool> toolOpt = amapTool();
        if (toolOpt.isEmpty()) {
            return "定位功能未配置高德 key，请提示管理员配置 external_tool 表中的 amap 密钥";
        }
        ExternalToolConfigService.ExternalTool tool = toolOpt.get();
        try {
            JsonNode data = MAPPER.readTree(amapGet(tool, "/v3/ip"));
            if (!"1".equals(data.path("status").asText())) {
                return "定位失败：" + data.path("info").asText("未知错误") + "，请向用户说明";
            }
            String province = data.path("province").asText("").trim();
            String city = data.path("city").asText("").trim();
            String adcode = data.path("adcode").asText("").trim();
            String rectangle = data.path("rectangle").asText("").trim();
            String desc;
            if (city.isEmpty() || "[]".equals(city)) {
                city = province.isEmpty() ? "未知城市" : province;
                desc = String.format("当前位置（IP定位，城市级）：%s", city);
            } else {
                desc = String.format("当前位置（IP定位，城市级）：\n- 省份：%s\n- 城市：%s\n- 行政区划代码：%s", province, city, adcode);
            }
            // rectangle 形如 "lng1,lat1;lng2,lat2"，取中心点供地图打点
            String center = "";
            if (rectangle.contains(";")) {
                String[] seg = rectangle.split(";");
                if (seg.length == 2) {
                    String[] a = seg[0].split(","), b = seg[1].split(",");
                    if (a.length == 2 && b.length == 2) {
                        try {
                            center = String.format("%.6f,%.6f",
                                    (Double.parseDouble(a[0]) + Double.parseDouble(b[0])) / 2,
                                    (Double.parseDouble(a[1]) + Double.parseDouble(b[1])) / 2);
                        } catch (NumberFormatException ignore) { /* 中心点解析失败则不带地图 */ }
                    }
                }
            }
            String marker = "";
            if (!center.isEmpty()) {
                marker = "\n[AMAP]{\"type\":\"poi\",\"city\":" + MAPPER.valueToTree(city)
                        + ",\"pois\":[{\"name\":" + MAPPER.valueToTree(city + "（当前所在城市）")
                        + ",\"address\":\"IP定位结果，精确到城市级别\",\"location\":\"" + center + "\"}]}[/AMAP]";
            }
            return desc + "\n\n如需精确位置，请让用户补充具体地址，或用 searchPlace 搜索目标地点。" + marker;
        } catch (Exception e) {
            log.warn("IP定位失败", e);
            return "定位出错：" + e.getMessage() + "，请向用户说明情况";
        }
    }

    @Tool(description = "在高德地图搜索地点（POI），返回名称、地址、坐标。当用户询问\"某地在哪\"、查找某个建筑/餐厅/车站/景点，或路线规划前需要确认地点坐标时调用。keywords 传地点关键词如\"五一广场\"、\"黄兴路步行街\"，city 传中文城市名限定搜索范围（必填，避免搜到外地同名地点）。")
    public String searchPlace(
            @ToolParam(description = "地点关键词，如 五一广场、岳麓山") String keywords,
            @ToolParam(description = "中文城市名，如 长沙、北京") String city) {
        Optional<ExternalToolConfigService.ExternalTool> toolOpt = amapTool();
        if (toolOpt.isEmpty()) {
            return "地点搜索功能未配置高德 key，请提示管理员到高德开放平台(lbs.amap.com)申请 Web 服务 key，"
                    + "并用主应用的 ExternalToolService.saveApiKey(\"amap\", key) 写入 external_tool 表";
        }
        ExternalToolConfigService.ExternalTool tool = toolOpt.get();
        try {
            JsonNode data = MAPPER.readTree(amapGet(tool, "/v3/place/text",
                    "keywords", keywords, "city", city, "citylimit", "true", "offset", "5"));
            if (!"1".equals(data.path("status").asText())) {
                return "地点搜索失败：" + data.path("info").asText("未知错误") + "，请向用户说明";
            }
            JsonNode pois = data.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return "未在" + city + "找到与「" + keywords + "」相关的地点，请向用户确认名称或换个说法";
            }
            List<String> parts = new ArrayList<>();
            List<String> markers = new ArrayList<>();
            for (int i = 0; i < pois.size(); i++) {
                JsonNode p = pois.get(i);
                String district = Stream.of(p.path("pname"), p.path("cityname"), p.path("adname"))
                        .map(n -> n.asText("").trim()).filter(s -> !s.isEmpty())
                        .collect(Collectors.joining(" "));
                String addr = p.path("address").asText("").trim();
                parts.add(String.format("%d. %s\n   地址：%s%s\n   坐标：%s",
                        i + 1, p.path("name").asText(),
                        district.isEmpty() ? "" : district + " ",
                        addr.isEmpty() ? "（无详细地址）" : addr,
                        p.path("location").asText("?")));
                markers.add(String.format("{\"name\":%s,\"address\":%s,\"location\":\"%s\"}",
                        MAPPER.valueToTree(p.path("name").asText()),
                        MAPPER.valueToTree(addr),
                        p.path("location").asText("")));
            }
            // 结构化标记：前端据此渲染交互式地图（定位打点），模型正文不受影响
            String marker = "\n[AMAP]{\"type\":\"poi\",\"city\":" + MAPPER.valueToTree(city)
                    + ",\"pois\":[" + String.join(",", markers) + "]}[/AMAP]";
            return String.join("\n", parts) + "\n\n坐标可直接作为 planRoute 的 origin/destination 参数。" + marker;
        } catch (Exception e) {
            log.warn("地点搜索失败 keywords={} city={}", keywords, city, e);
            return "地点搜索出错：" + e.getMessage() + "，请向用户说明情况";
        }
    }

    @Tool(description = "高德路线规划：计算从起点到终点的路线，返回耗时、距离和行进/换乘说明。用户问\"从A到B怎么走/多远/多久\"时调用。mode 取 transit(公交地铁)/walking(步行)/driving(驾车) 之一，默认公交。origin/destination 传地名（如\"长沙火车站\"）或\"经度,纬度\"坐标（searchPlace 返回的坐标可直接用）；city 传起点所在中文城市名，公交换乘时必填。")
    public String planRoute(
            @ToolParam(description = "起点：地名或\"经度,纬度\"坐标") String origin,
            @ToolParam(description = "终点：地名或\"经度,纬度\"坐标") String destination,
            @ToolParam(description = "起点所在中文城市名，如 长沙") String city,
            @ToolParam(description = "出行方式：transit / walking / driving") String mode) {
        Optional<ExternalToolConfigService.ExternalTool> toolOpt = amapTool();
        if (toolOpt.isEmpty()) {
            return "路线规划功能未配置高德 key，请提示管理员到高德开放平台(lbs.amap.com)申请 Web 服务 key，"
                    + "并用主应用的 ExternalToolService.saveApiKey(\"amap\", key) 写入 external_tool 表";
        }
        ExternalToolConfigService.ExternalTool tool = toolOpt.get();
        String m = mode == null ? "transit" : mode.trim().toLowerCase();
        if (!List.of("transit", "walking", "driving").contains(m)) {
            return "不支持的出行方式「" + mode + "」，请用 transit / walking / driving 之一";
        }
        try {
            String from = resolvePoint(tool, origin, city);
            if (from == null) {
                return "无法定位起点「" + origin + "」，请向用户确认更具体的地址，或先用 searchPlace 搜索";
            }
            String to = resolvePoint(tool, destination, city);
            if (to == null) {
                return "无法定位终点「" + destination + "」，请向用户确认更具体的地址，或先用 searchPlace 搜索";
            }
            RoutePlan rp;
            switch (m) {
                case "walking":  rp = walkPlan(tool, from, to); break;
                case "driving":  rp = drivePlan(tool, from, to); break;
                default:         rp = transitPlan(tool, from, to, city); break;
            }
            // 结构化标记：前端据此渲染交互式地图（路线规划 + 多方案切换），模型正文不受影响
            String marker = "\n[AMAP]{\"type\":\"route\",\"mode\":\"" + m + "\",\"city\":" + MAPPER.valueToTree(city)
                    + ",\"origin\":" + MAPPER.valueToTree(origin) + ",\"destination\":" + MAPPER.valueToTree(destination)
                    + ",\"from\":\"" + from + "\",\"to\":\"" + to + "\""
                    + (rp.plansJson().isEmpty() ? "" : ",\"plans\":" + rp.plansJson())
                    + "}[/AMAP]";
            return rp.text() + marker;
        } catch (Exception e) {
            log.warn("路线规划失败 origin={} destination={} mode={}", origin, destination, m, e);
            return "路线规划出错：" + e.getMessage() + "，请向用户说明情况";
        }
    }

    // ==================== 配送驾车路线（结构化，后端内部消费，无 [AMAP] 标记） ====================

    /**
     * 主应用 AmapRouteService 的 MCP 化替代：逻辑原样（/v3/direction/driving，
     * extensions=base，strategy=0，取 paths[0]）。MCP 文本结果不能为 null，
     * 失败统一返回空串，调用方按"空串 = null"处理（保持 route_json 落空语义不变）。
     */
    @Tool(description = "驾车路线规划（结构化JSON）：返回 {distance,duration,distanceText,durationText,steps[]}，"
            + "供后端配送模块存入 route_json 使用；坐标均传十进制度数。失败返回空字符串。")
    public String driveRouteJson(
            @ToolParam(description = "起点经度") double originLng,
            @ToolParam(description = "起点纬度") double originLat,
            @ToolParam(description = "终点经度") double destLng,
            @ToolParam(description = "终点纬度") double destLat) {
        Optional<ExternalToolConfigService.ExternalTool> toolOpt = amapTool();
        if (toolOpt.isEmpty()) {
            log.warn("高德 Web 服务 key 未配置，跳过路线规划");
            return "";
        }
        ExternalToolConfigService.ExternalTool tool = toolOpt.get();
        try {
            JsonNode root = MAPPER.readTree(amapGet(tool, "/v3/direction/driving",
                    "origin", originLng + "," + originLat,
                    "destination", destLng + "," + destLat,
                    "extensions", "base", "strategy", "0"));
            if (!"1".equals(root.path("status").asText())) {
                log.warn("高德路线规划失败：{}", root.path("info").asText());
                return "";
            }
            JsonNode paths = root.path("route").path("paths");
            if (!paths.isArray() || paths.isEmpty()) {
                return "";
            }
            JsonNode best = paths.get(0);
            String distance = best.path("distance").asText(); // 米
            String duration = best.path("duration").asText(); // 秒

            List<String> steps = new ArrayList<>();
            JsonNode stepsArr = best.path("steps");
            if (stepsArr.isArray()) {
                for (JsonNode s : stepsArr) {
                    String instruction = s.path("instruction").asText();
                    if (instruction != null && !instruction.isBlank()) {
                        steps.add(instruction);
                    }
                }
            }

            ObjectNode result = MAPPER.createObjectNode();
            result.put("distance", distance);
            result.put("duration", duration);
            result.put("distanceText", formatDistance(distance));
            result.put("durationText", formatDuration(duration));
            result.putArray("steps").addAll(steps.stream().map(MAPPER.getNodeFactory()::textNode).toList());
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            log.error("高德路线规划异常", e);
            return "";
        }
    }

    // ==================== 内部辅助 ====================

    /** 路线规划结果：text 给模型阅读，plansJson 供前端地图渲染多方案切换（构造失败时为空串） */
    private record RoutePlan(String text, String plansJson) {}

    /** 取已配置密钥的 amap 工具配置，未配置/无密钥返回 empty */
    private Optional<ExternalToolConfigService.ExternalTool> amapTool() {
        return externalToolConfigService.findTool("amap")
                .filter(ExternalToolConfigService.ExternalTool::hasApiKey);
    }

    /** 高德 GET 请求：自动带鉴权 key（参数名取 auth_key_name，缺省 key），kv 为成对的查询参数 */
    private static String amapGet(ExternalToolConfigService.ExternalTool tool, String path, String... kv) throws Exception {
        String keyParam = tool.authKeyName() == null || tool.authKeyName().isBlank()
                ? "key" : tool.authKeyName();
        StringBuilder url = new StringBuilder(tool.baseUrl()).append(path).append('?')
                .append(keyParam).append('=').append(URLEncoder.encode(tool.apiKey(), StandardCharsets.UTF_8));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            url.append('&').append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return get(url.toString(), tool.timeoutMs());
    }

    /** 地名 → "经度,纬度"；已是坐标（lng,lat）则原样返回，定位失败返回 null */
    private static String resolvePoint(ExternalToolConfigService.ExternalTool tool, String value, String city) throws Exception {
        String v = value == null ? "" : value.trim();
        if (v.matches("\\d{1,3}\\.\\d+,\\d{1,3}\\.\\d+")) {
            return v;
        }
        JsonNode data = MAPPER.readTree(amapGet(tool, "/v3/geocode/geo", "address", v, "city", city));
        JsonNode gs = data.path("geocodes");
        if (!"1".equals(data.path("status").asText()) || !gs.isArray() || gs.isEmpty()) {
            return null;
        }
        String loc = gs.get(0).path("location").asText("");
        return loc.isEmpty() ? null : loc;
    }

    /** 公交换乘：/v3/direction/transit/integrated，最多展示 3 个方案 */
    private static RoutePlan transitPlan(ExternalToolConfigService.ExternalTool tool, String from, String to, String city)
            throws Exception {
        JsonNode data = MAPPER.readTree(amapGet(tool, "/v3/direction/transit/integrated",
                "origin", from, "destination", to, "city", city, "extensions", "all"));
        if (!"1".equals(data.path("status").asText())) {
            return new RoutePlan("公交路线查询失败：" + data.path("info").asText("未知错误") + "，请向用户说明", "");
        }
        JsonNode transits = data.path("route").path("transits");
        if (!transits.isArray() || transits.isEmpty()) {
            return new RoutePlan("两地之间未找到公交方案（可能距离过近建议步行，或超出该城市公交覆盖范围）", "");
        }
        List<String> plans = new ArrayList<>();
        ArrayNode jsonPlans = MAPPER.createArrayNode();
        for (int i = 0; i < Math.min(3, transits.size()); i++) {
            JsonNode t = transits.get(i);
            String duration = fmtDur(t.path("duration").asLong(0));
            StringBuilder sb = new StringBuilder(String.format("方案%d：约%s，步行%s",
                    i + 1, duration, fmtDist(t.path("walking_distance").asLong(0))));
            int step = 0;
            ArrayNode jsonSteps = MAPPER.createArrayNode();
            for (JsonNode seg : t.path("segments")) {
                JsonNode bl0 = seg.path("bus").path("buslines").path(0);
                if (!bl0.isMissingNode() && !bl0.path("name").asText("").isEmpty()) {
                    String line = String.format("乘坐%s：%s → %s（途经%s站）",
                            bl0.path("name").asText(),
                            bl0.path("departure_stop").path("name").asText("?"),
                            bl0.path("arrival_stop").path("name").asText("?"),
                            bl0.path("via_num").asText("?"));
                    sb.append(String.format("\n  %d. %s", ++step, line));
                    jsonSteps.add(line);
                } else if (seg.path("walking").path("distance").asLong(0) > 0) {
                    String walk = "步行" + fmtDist(seg.path("walking").path("distance").asLong(0));
                    sb.append(String.format("\n  %d. %s", ++step, walk));
                    jsonSteps.add(walk);
                }
            }
            plans.add(sb.toString());
            ObjectNode jp = jsonPlans.addObject();
            jp.put("title", "方案" + (i + 1));
            jp.put("duration", duration);
            jp.put("walkDistance", fmtDist(t.path("walking_distance").asLong(0)));
            jp.set("steps", jsonSteps);
        }
        return new RoutePlan(String.join("\n\n", plans), jsonPlans.toString());
    }

    /** 步行：/v3/direction/walking，展示距离耗时 + 前 6 步指引 */
    private static RoutePlan walkPlan(ExternalToolConfigService.ExternalTool tool, String from, String to) throws Exception {
        JsonNode data = MAPPER.readTree(amapGet(tool, "/v3/direction/walking", "origin", from, "destination", to));
        if (!"1".equals(data.path("status").asText())) {
            return new RoutePlan("步行路线查询失败：" + data.path("info").asText("未知错误") + "，请向用户说明", "");
        }
        JsonNode path = data.path("route").path("paths").path(0);
        if (path.isMissingNode()) {
            return new RoutePlan("两地之间未找到步行路线（可能距离过远）", "");
        }
        JsonNode steps = path.path("steps");
        String duration = fmtDur(path.path("duration").asLong(0));
        String distance = fmtDist(path.path("distance").asLong(0));
        StringBuilder sb = new StringBuilder(String.format("步行全程约%s，需时约%s。", distance, duration));
        ArrayNode jsonSteps = MAPPER.createArrayNode();
        int shown = Math.min(6, steps.size());
        for (int i = 0; i < shown; i++) {
            sb.append("\n").append(i + 1).append(". ").append(stripHtml(steps.get(i).path("instruction").asText()));
        }
        if (steps.size() > shown) {
            sb.append("\n……共").append(steps.size()).append("步");
        }
        for (int i = 0; i < Math.min(12, steps.size()); i++) {
            jsonSteps.add((i + 1) + ". " + stripHtml(steps.get(i).path("instruction").asText()));
        }
        ObjectNode jp = MAPPER.createObjectNode();
        jp.put("title", "步行方案");
        jp.put("duration", duration);
        jp.put("distance", distance);
        jp.set("steps", jsonSteps);
        return new RoutePlan(sb.toString(), "[" + jp + "]");
    }

    /** 驾车：/v3/direction/driving，展示距离耗时策略与过路费 */
    private static RoutePlan drivePlan(ExternalToolConfigService.ExternalTool tool, String from, String to) throws Exception {
        JsonNode data = MAPPER.readTree(amapGet(tool, "/v3/direction/driving", "origin", from, "destination", to));
        if (!"1".equals(data.path("status").asText())) {
            return new RoutePlan("驾车路线查询失败：" + data.path("info").asText("未知错误") + "，请向用户说明", "");
        }
        JsonNode path = data.path("route").path("paths").path(0);
        if (path.isMissingNode()) {
            return new RoutePlan("两地之间未找到驾车路线", "");
        }
        String tolls = path.path("tolls").asText("0");
        String duration = fmtDur(path.path("duration").asLong(0));
        String distance = fmtDist(path.path("distance").asLong(0));
        String text = String.format("驾车全程约%s，需时约%s（策略：%s%s）。",
                distance, duration, path.path("strategy").asText("默认"),
                "0".equals(tolls) ? "，无过路费" : "，过路费约" + tolls + "元");
        ArrayNode jsonSteps = MAPPER.createArrayNode();
        JsonNode steps = path.path("steps");
        for (int i = 0; i < Math.min(15, steps.size()); i++) {
            jsonSteps.add((i + 1) + ". " + stripHtml(steps.get(i).path("instruction").asText()));
        }
        ObjectNode jp = MAPPER.createObjectNode();
        jp.put("title", "驾车方案");
        jp.put("duration", duration);
        jp.put("distance", distance);
        jp.put("tolls", "0".equals(tolls) ? "无过路费" : "过路费约" + tolls + "元");
        jp.set("steps", jsonSteps);
        return new RoutePlan(text, "[" + jp + "]");
    }

    /** 米 → "850米" / "2.3公里" */
    private static String fmtDist(long meters) {
        return meters >= 1000 ? String.format("%.1f公里", meters / 1000.0) : meters + "米";
    }

    /** 秒 → "8分钟" / "1小时25分" */
    private static String fmtDur(long seconds) {
        if (seconds <= 0) return "未知";
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h > 0 ? h + "小时" + (m > 0 ? m + "分" : "") : (m > 0 ? m + "分钟" : "1分钟内");
    }

    /** 去掉高德指引里的 HTML 标签（如 <b>） */
    private static String stripHtml(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").trim();
    }

    /** 高德返回 "0600"/"2230" 格式，转成 "06:00" */
    private static String fmtTime(String t) {
        return (t != null && t.length() == 4) ? t.substring(0, 2) + ":" + t.substring(2)
                : (t == null || t.isBlank() ? "未知" : t);
    }

    /** 配送路线 JSON 字段：米 → "850 米" / "2.3 公里"（自主应用 AmapRouteService 迁入，格式含空格，与 fmtDist 不同） */
    private static String formatDistance(String meters) {
        try {
            int m = Integer.parseInt(meters);
            if (m < 1000) return m + " 米";
            return String.format("%.1f 公里", m / 1000.0);
        } catch (NumberFormatException e) {
            return meters + " 米";
        }
    }

    /** 配送路线 JSON 字段：秒 → "30 秒" / "25 分钟" / "1 小时 5 分钟"（自 AmapRouteService 迁入） */
    private static String formatDuration(String seconds) {
        try {
            int s = Integer.parseInt(seconds);
            if (s < 60) return s + " 秒";
            int min = s / 60;
            if (min < 60) return min + " 分钟";
            int h = min / 60;
            int remainMin = min % 60;
            return h + " 小时" + (remainMin > 0 ? remainMin + " 分钟" : "");
        } catch (NumberFormatException e) {
            return seconds + " 秒";
        }
    }

    private static String get(String url, int timeoutMs) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
