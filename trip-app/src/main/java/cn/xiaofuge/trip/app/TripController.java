package cn.xiaofuge.trip.app;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TripController {

    private final TripStore store;

    public TripController(TripStore store) {
        this.store = store;
    }

    /** 核心：三方案规划（最快/最便宜/少步行） */
    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> plan(@RequestBody Map<String, Object> body) {
        String from = str(body.get("from")), to = str(body.get("to"));
        if (from.isBlank() || to.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "出发地和目的地不能为空"));
        }
        try {
            return ResponseEntity.ok(Map.of("code", 0, "data", store.plan(from, to, str(body.get("when")))));
        } catch (IllegalArgumentException e) {
            return error(e);
        }
    }

    /** 地铁两站路径 */
    @GetMapping("/metro/path")
    public ResponseEntity<Map<String, Object>> metroPath(@RequestParam String from, @RequestParam String to) {
        TripStore.MetroPath p = store.metroPath(from, to);
        if (p == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "未找到可达路径，请确认站名（可用 /api/search 模糊搜索）"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", p));
    }

    /** 地铁线网（供前端画图与 AI 介绍线路） */
    @GetMapping("/metro/lines")
    public Map<String, Object> metroLines() {
        return Map.of("code", 0, "data", store.metroLines());
    }

    /** 公交：按站名查线路 / 按线路名查 / 不带参数返回全部线路 */
    @GetMapping("/bus")
    public Map<String, Object> bus(@RequestParam(required = false) String stop,
                                   @RequestParam(required = false) String line) {
        if (line != null && !line.isBlank()) {
            return Map.of("code", 0, "data", store.busByLineName(line));
        }
        return Map.of("code", 0, "data", store.busByStop(stop));
    }

    /** 公交线路详情 */
    @GetMapping("/bus/{code}")
    public ResponseEntity<Map<String, Object>> busDetail(@PathVariable String code) {
        TripStore.BusLine b = store.busByCode(code);
        if (b == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "线路不存在: " + code));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", b));
    }

    /** 打车费用估算 */
    @GetMapping("/taxi/estimate")
    public ResponseEntity<Map<String, Object>> taxi(@RequestParam(defaultValue = "eco") String type,
                                                    @RequestParam double km,
                                                    @RequestParam(required = false) Boolean rushHour) {
        if (km <= 0 || km > 200) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "里程需在 0~200km 之间"));
        }
        boolean rush = rushHour == null ? TripStore.driveMinutes(km, true) > 0 && isRushNow() : rushHour;
        int minutes = TripStore.driveMinutes(km, rush);
        return ResponseEntity.ok(Map.of("code", 0, "data",
                store.taxiQuote(type, km, minutes, rush)));
    }

    /** 打车三档车型对比 */
    @GetMapping("/taxi/compare")
    public Map<String, Object> taxiCompare(@RequestParam double km,
                                           @RequestParam(required = false) Boolean rushHour) {
        boolean rush = rushHour == null ? isRushNow() : rushHour;
        int minutes = TripStore.driveMinutes(km, rush);
        return Map.of("code", 0, "data",
                store.taxiTypes().stream().map(t -> store.taxiQuote(t.code(), km, minutes, rush)).toList());
    }

    /** 站点/地标模糊搜索 */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(required = false) String keyword) {
        return Map.of("code", 0, "data", store.searchPlace(keyword));
    }

    /** 地标列表 */
    @GetMapping("/landmarks")
    public Map<String, Object> landmarks() {
        return Map.of("code", 0, "data", store.landmarks());
    }

    private boolean isRushNow() {
        int h = java.time.LocalTime.now().getHour();
        return (h >= 7 && h < 10) || (h >= 16 && h < 19);
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private ResponseEntity<Map<String, Object>> error(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", 1, "message", e.getMessage()));
    }
}
