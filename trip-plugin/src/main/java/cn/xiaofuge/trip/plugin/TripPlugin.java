package cn.xiaofuge.trip.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 城市出行规划插件：把 trip-app 的 REST API 注册为 DSH Agent 工具。
 * 插件不直连数据，全部通过 HTTP 调业务应用，守住安全边界。
 */
public class TripPlugin extends AbstractHarnessPlugin {

    public static final String PLUGIN_ID = "trip-assistant";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public TripPlugin() { super(PLUGIN_ID); }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new PlanTripTool(),
                new MetroRouteTool(),
                new BusQueryTool(),
                new TaxiEstimateTool(),
                new StationSearchTool());
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("trip-capabilities", 20, """
                ## 城市出行规划助手（杭城出行）
                - 用户问"怎么去/帮我规划/通勤方案/几点出门" → plan_trip（从 from 到 to，可带出发时间 hour 与偏好 pref）
                - 用户问"坐地铁怎么换乘/几号线" → metro_route
                - 用户问"有哪些公交/怎么坐公交/首末班" → bus_query（stop 传站名查可乘线路，或 line 传线路号查详情）
                - 用户问"打车多少钱/打车划算吗" → taxi_estimate
                - 用户说的出发地/目的地不确定（地标、商圈名）→ 先 station_search 定位，再规划
                - 回答要求：
                  1) 三方案对比时用小表格或分点，突出「最快/最便宜/少步行」差异和推荐理由
                  2) 涉及金额一律 ¥ + 一位小数；时间一律用「分钟」并说明是否含步行与等车
                  3) 数据必须来自工具返回，禁止编造站点与价格；两个地点查不到时如实说明并建议附近的地铁站
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__")) {
                return PluginHookResult.context("audit: trip tool call.");
            }
            return null;
        });
    }

    // ---- HTTP 辅助（带超时与异常兜底） ----

    private String get(String pathWithQuery, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + pathWithQuery)).GET().build());
    }

    private String post(String path, String jsonBody, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("appBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("TRIP_APP_BASE_URL", "http://127.0.0.1:18081")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return failJson(resp.statusCode(), resp.body());
            return resp.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String appendParam(String path, String name, String value) {
        if (value == null || value.isBlank()) return path;
        return path + (path.contains("?") ? "&" : "?") + name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---- 工具定义 ----

    private class PlanTripTool extends AbstractTool {
        @Override public String name() { return "plan_trip"; }
        @Override public String description() {
            return "城市出行聚合规划核心工具：给定出发地与目的地，一次返回最快/最便宜/少步行三种方案（地铁/公交/打车组合），"
                    + "每种方案含总耗时、票价、步行时间与分步指引。何时必须调用：用户问怎么去、通勤方案、对比出行方式。"
                    + "何时不要调用：用户只问单一方式的细节（用 metro_route/bus_query/taxi_estimate）。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("from", stringSchema("出发地：地铁站名、公交站名或地标（如 西湖·断桥残雪、湖滨银泰 in77）"))
                    .prop("to", stringSchema("目的地：同上"))
                    .prop("hour", stringSchema("出发时间，格式 HH:mm，如 08:30；不填默认现在"))
                    .prop("pref", stringSchema("偏好：fastest（最快，默认）/ cheapest（最便宜）/ leastWalk（少步行）"))
                    .required("from", "to")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"from\":\"" + json(str(args, "from"))
                    + "\",\"to\":\"" + json(str(args, "to"))
                    + "\",\"hour\":\"" + json(str(args, "hour"))
                    + "\",\"pref\":\"" + json(str(args, "pref")) + "\"}";
            return ok(post("/api/plan", body, args));
        }
    }

    private class MetroRouteTool extends AbstractTool {
        @Override public String name() { return "metro_route"; }
        @Override public String description() {
            return "查询两个地铁站之间的地铁乘车路径：途经站点、换乘站与换乘线路、总耗时、里程与票价（按里程阶梯计价）。"
                    + "何时必须调用：用户明确问坐地铁、几号线、怎么换乘。跨线时会给出换乘站。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("from", stringSchema("出发地铁站名"))
                    .prop("to", stringSchema("到达地铁站名"))
                    .required("from", "to")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get(appendParam(appendParam("/api/metro/path", "from", str(args, "from")),
                    "to", str(args, "to")), args));
        }
    }

    private class BusQueryTool extends AbstractTool {
        @Override public String name() { return "bus_query"; }
        @Override public String description() {
            return "公交查询双模式：stop 传站名→返回该站可乘线路（首末班/票价/发车间隔）；line 传线路号→返回该线路完整站点序列。"
                    + "何时必须调用：用户问某站有哪些公交、某路公交经过哪些站、公交首末班时间。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("stop", stringSchema("按站名查询（模糊匹配），如 黄龙。stop 与 line 二选一"))
                    .prop("line", stringSchema("按线路号查询，如 194 或 B1。stop 与 line 二选一"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get(appendParam(appendParam("/api/bus", "stop", str(args, "stop")),
                    "line", str(args, "line")), args));
        }
    }

    private class TaxiEstimateTool extends AbstractTool {
        @Override public String name() { return "taxi_estimate"; }
        @Override public String description() {
            return "打车费用估算：按里程返回经济型/优享/六座商务三档车型的价格明细（起步价+里程费+时长费，高峰期有溢价系数）。"
                    + "何时必须调用：用户问打车多少钱、打车与地铁哪个划算。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("km", stringSchema("里程（公里），大于 0"))
                    .prop("rushHour", stringSchema("是否高峰：true（早晚高峰，×1.3）/ false（默认平峰）"))
                    .required("km")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String km = str(args, "km");
            try {
                km = String.valueOf(Double.parseDouble(km));
            } catch (Exception ignore) { }
            String rush = str(args, "rushHour");
            if (rush.isEmpty()) rush = "false";
            return ok(get("/api/taxi/compare?km=" + km + "&rushHour=" + rush, args));
        }
    }

    private class StationSearchTool extends AbstractTool {
        @Override public String name() { return "station_search"; }
        @Override public String description() {
            return "站点/地标模糊搜索：把用户口语化的地名（商圈、景区、大学）定位到规范站点名，如\"西湖边的商场\"→西湖·断桥残雪。"
                    + "何时必须调用：规划前用户给的出发地/目的地不是标准站名、或 plan_trip 返回查不到时先用本工具定位。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("keyword", stringSchema("关键词，如 龙翔、良渚、西湖"))
                    .required("keyword")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get(appendParam("/api/search", "keyword", str(args, "keyword")), args));
        }
    }
}
