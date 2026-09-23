package cn.xiaofuge.trip.app;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 城市出行规划内存数据与规划引擎（重启即还原种子）。
 * 地铁 3 线 24 站（真实杭州风格站名与里程）、公交 12 线、打车 3 档车型。
 * plan() 聚合出「最快 / 最便宜 / 少步行」三方案，全部数字由线网实时计算。
 */
@Component
public class TripStore {

    // ================= 地铁 =================

    public record MetroLine(String id, String name, String color, List<String> stations, List<Double> km) {
        /** 站 i -> 站 i+1 的运行分钟（按 2.2 min/km + 停站 0.5 估算，杭州地铁实测平均） */
        public List<Integer> perHopMinutes() {
            List<Integer> out = new ArrayList<>();
            for (Double k : km) out.add((int) Math.round(k * 2.2 + 0.5));
            return out;
        }
    }

    private final Map<String, MetroLine> metroLines = new LinkedHashMap<>();
    /** 站名（小写） -> 站名规范写法，多线换乘站只留一份 */
    private final Map<String, String> stationIndex = new ConcurrentHashMap<>();

    // ================= 公交 =================

    public record BusLine(String code, String name, String from, String to,
                          String firstBus, String lastBus, double fare,
                          int headwayMin, List<String> stops, double walkToStopMin) {}

    private final Map<String, BusLine> busLines = new LinkedHashMap<>();

    // ================= 打车 =================

    public record TaxiType(String code, String label, double baseFare, double baseKm,
                           double perKm, double perMin, double longTripPerKm, double longTripFromKm,
                           String carDesc) {}

    private final Map<String, TaxiType> taxiTypes = new LinkedHashMap<>();

    // ================= 地标（非站点目的地：步行接驳） =================

    public record Landmark(String name, String nearestStation, String nearestLine,
                           double walkMin, String area) {}

    private final Map<String, Landmark> landmarks = new LinkedHashMap<>();

    public TripStore() {
        seedMetro();
        seedBus();
        seedTaxi();
        seedLandmarks();
    }

    private void seedMetro() {
        // 1 号线：湘湖 — 萧山国际机场（主干线，贯穿主城）
        MetroLine l1 = new MetroLine("m1", "1号线", "#c23a30",
                List.of("湘湖", "滨康路", "西兴", "滨和路", "江陵路", "近江", "婺江路", "城站",
                        "定安路", "龙翔桥", "凤起路", "武林广场", "西湖文化广场", "打铁关", "闸弄口",
                        "火车东站", "彭埠", "翁梅", "余杭高铁", "南苑", "临平", "萧山国际机场"),
                List.of(1.6, 1.8, 1.2, 1.5, 2.1, 1.4, 1.7, 1.3, 1.6, 0.8, 0.8, 1.4, 1.8, 1.5, 2.0, 2.3, 3.1, 2.8, 1.9, 1.7, 14.5));
        // 2 号线：朝阳 — 良渚（西北—东南对角线）
        MetroLine l2 = new MetroLine("m2", "2号线", "#2e6bb0",
                List.of("朝阳", "人民路", "杭发厂", "人民广场", "建设一路", "钱江世纪城",
                        "盈丰路", "钱江路", "庆春广场", "庆菱路", "建国北路", "中河北路",
                        "凤起路", "武林门", "沈塘桥", "学院路", "古翠路", "丰潭路", "三坝", "良渚"),
                List.of(1.7, 1.3, 1.2, 1.8, 2.4, 1.6, 1.5, 1.9, 1.1, 1.6, 1.3, 1.4, 1.5, 1.2, 1.7, 1.2, 1.8, 2.0, 9.6));
        // 10 号线：逸盛路 — 浙大（北部横向线，含换乘）
        MetroLine l10 = new MetroLine("m10", "10号线", "#3f9d6b",
                List.of("逸盛路", "杭行路", "祥园路", "和睦", "花园岗", "渡驾桥",
                        "北大桥", "和睦新村", "学院路", "文三路", "沈塘桥", "浙大"),
                List.of(1.5, 1.4, 1.3, 1.6, 1.5, 1.4, 1.2, 1.8, 1.3, 1.1, 1.9));
        for (MetroLine line : List.of(l1, l2, l10)) {
            metroLines.put(line.id, line);
            for (String s : line.stations) stationIndex.putIfAbsent(s.toLowerCase(), s);
        }
    }

    private void seedBus() {
        putBus(new BusLine("b01", "194 路", "钱江湾花园", "黄龙体育中心",
                "06:00", "21:30", 2.0, 8,
                List.of("钱江湾花园", "滨文中心站", "江南大道东信大道口", "联庄", "复兴路紫花路口",
                        "南星桥", "凤山门", "万松岭", "吴山广场", "西湖大道", "市三医院北",
                        "丰乐桥南", "胜利剧院", "少年宫", "松木场", "黄龙洞", "黄龙体育中心"),
                4));
        putBus(new BusLine("b02", "B1 快速公交", "黄龙公交站", "下沙高教东区",
                "05:30", "22:30", 4.0, 5,
                List.of("黄龙公交站", "八字桥", "武林门马塍路口", "武林广场", "中山北园",
                        "公交总公司", "闸弄口新村", "艮新天桥南", "彭埠", "高沙",
                        "文泽路学源街口", "下沙高教文泽站", "下沙高教东区"),
                5));
        putBus(new BusLine("b03", "12 路", "勾庄", "开元路",
                "05:50", "22:00", 2.0, 10,
                List.of("勾庄", "祥符桥", "莫干山路祥园路口", "北大桥", "大关桥西",
                        "仓基上", "卖鱼桥", "信义坊", "湖墅路沈塘桥", "密渡桥路口",
                        "武林门湖墅路口", "武林小广场", "延安新村", "孩儿巷", "井亭桥", "开元路"),
                3));
        putBus(new BusLine("b04", "87 路", "浙大公交站", "安吉山",
                "06:10", "20:40", 2.0, 12,
                List.of("浙大公交站", "玉古路求智路口", "浙大附中", "黄龙洞", "松木场",
                        "省府大楼", "武林门西", "昌化新村", "密渡桥路口", "沈塘桥", "文三路马塍路口",
                        "花园西村", "宋江村", "古荡新村", "古荡", "紫荆花路文一西路口", "安吉山"),
                3));
        putBus(new BusLine("b05", "318 路", "临平南站", "九堡客运中心",
                "06:20", "21:00", 3.0, 15,
                List.of("临平南站", "临平大厦", "南苑街迎宾路口", "余杭区第一人民医院", "临平星光街",
                        "星桥南路天都路口", "天都城", "吴家厍", "临平大道星发街口", "乔司街道",
                        "永玄路", "九福路乔司街", "九堡街道", "九堡客运中心"),
                6));
        putBus(new BusLine("b06", "115 路", "滨盛路东信大道口", "城站火车站",
                "06:00", "22:20", 2.0, 9,
                List.of("滨盛路东信大道口", "东信大道滨和路口", "西兴桥头", "江边", "南环路江边",
                        "长河路滨康路口", "滨康路聚工路口", "江陵路滨和路口", "星民村", "西兴",
                        "共联村", "清江路钱江路口", "观音塘小区", "解放东路", "城站火车站"),
                4));
        putBus(new BusLine("b07", "51 路环线", "湖滨", "湖滨（环线）",
                "06:30", "21:00", 2.0, 12,
                List.of("湖滨", "东坡路平海路口", "少年宫", "断桥", "西泠桥", "岳庙",
                        "曲院风荷", "杭州花圃", "浴鹄湾", "苏堤", "净寺", "长桥", "吴山广场", "湖滨"),
                2));
        putBus(new BusLine("b08", "720 路", "萧山 uid 商城", "湘湖公交站",
                "06:00", "21:10", 2.0, 11,
                List.of("萧山 uid 商城", "人民广场", "萧山体育馆", "崇化", "大通桥",
                        "东汪村", "湘湖旅游度假区", "越寨", "东湘村", "湘湖公交站"),
                5));
        putBus(new BusLine("b09", "94 路", "和睦新村", "火车东站西",
                "05:40", "22:50", 2.0, 8,
                List.of("和睦新村", "北大桥", "大关路口", "德胜新村", "绍兴路德胜路口",
                        "打铁关", "尧典桥路", "机神村", "闸弄口新村", "火车东站西"),
                3));
        putBus(new BusLine("b10", "139 路", "良渚文化村", "三坝地铁口",
                "06:20", "20:30", 2.0, 14,
                List.of("良渚文化村", "白鹭郡南", "良渚博物馆", "良渚", "崇福村",
                        "朱家斗", "周家里", "三墩镇政府", "三墩", "振华路西陈桥", "三坝地铁口"),
                6));
        putBus(new BusLine("b11", "28 路", "浙大公交站", "火车东站西",
                "05:50", "22:40", 2.0, 7,
                List.of("浙大公交站", "玉古路求是路口", "黄龙洞", "松木场", "武林门马塍路口",
                        "武林广场", "杭州大厦", "中北桥", "宝善桥", "公交总公司东", "闸弄口新村", "火车东站西"),
                3));
        putBus(new BusLine("b12", "194 区间快线", "黄龙体育中心", "江陵路地铁口",
                "07:00", "19:00", 3.0, 6,
                List.of("黄龙体育中心", "松木场", "市三医院北", "复兴路紫花路口", "联庄", "江陵路地铁口"),
                4));
    }

    /** 早晚高峰单列（首末班为分号拼接的通勤快线） */
    private void putBus(BusLine line) {
        busLines.put(line.code, line);
    }

    private void seedTaxi() {
        putTaxi(new TaxiType("eco", "经济型（滴滴快车/高德经济）", 12.0, 3, 2.5, 0.6, 3.2, 10,
                "大众朗逸 / 荣威 Ei5 同级，1 人通勤首选"));
        putTaxi(new TaxiType("premium", "优享型（礼橙专车）", 16.0, 3, 3.4, 0.8, 4.2, 10,
                "帕萨特 / 凯美瑞同级，车内安静，适合见客户"));
        putTaxi(new TaxiType("biz6", "六座商务（商务专车）", 24.0, 3, 5.2, 1.0, 6.0, 10,
                "别克 GL8，团队出行 / 带行李"));
    }

    private void putTaxi(TaxiType t) {
        taxiTypes.put(t.code, t);
    }

    private void seedLandmarks() {
        put(new Landmark("西湖·断桥残雪", "龙翔桥", "1号线", 9, "西湖景区"));
        put(new Landmark("杭州东站（高铁枢纽）", "火车东站", "1号线", 4, "枢纽"));
        put(new Landmark("武林商圈（杭州大厦/银泰）", "武林广场", "1号线", 5, "商圈"));
        put(new Landmark("黄龙体育中心", "学院路", "10号线", 12, "文体"));
        put(new Landmark("良渚博物院", "良渚", "2号线", 11, "文旅"));
        put(new Landmark("萧山国际机场 T4", "萧山国际机场", "1号线", 6, "枢纽"));
        put(new Landmark("钱江新城 CBD（来福士）", "钱江路", "2号线", 8, "CBD"));
        put(new Landmark("浙大玉泉校区", "浙大", "10号线", 7, "学区"));
        put(new Landmark("湖滨银泰 in77", "龙翔桥", "1号线", 3, "商圈"));
        put(new Landmark("临平银泰城", "临平", "1号线", 10, "商圈"));
        put(new Landmark("滨江区网易大厦", "江陵路", "1号线/6号线", 13, "产业园"));
        put(new Landmark("西溪湿地北门", "三坝", "2号线", 15, "景区"));
    }

    private void put(Landmark l) {
        landmarks.put(l.name().toLowerCase(), l);
    }

    // ================= 查询 API =================

    public List<MetroLine> metroLines() {
        return new ArrayList<>(metroLines.values());
    }

    public MetroLine metroLine(String id) {
        return metroLines.get(id);
    }

    public List<BusLine> busLines() {
        return new ArrayList<>(busLines.values());
    }

    public List<TaxiType> taxiTypes() {
        return new ArrayList<>(taxiTypes.values());
    }

    public List<Landmark> landmarks() {
        return new ArrayList<>(landmarks.values());
    }

    /** 站点/地标模糊搜索：先精确后模糊，返回 名称 + 类型（metro/landmark） */
    public List<Map<String, Object>> searchPlace(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            // 空关键词返回热门地点（地标 + 换乘站 + 部分地铁站），供前端下拉初始化
            List<Map<String, Object>> all = new ArrayList<>();
            for (Landmark l : landmarks.values()) all.add(placeEntry(l.name(), "landmark"));
            for (MetroLine line : metroLines.values()) {
                for (String s : line.stations) {
                    if (all.stream().noneMatch(m -> s.equals(m.get("name")))) all.add(placeEntry(s, "metro"));
                }
            }
            return all;
        }
        String k = keyword.trim().toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        // 地标精确优先
        Landmark lm = landmarks.get(k);
        if (lm != null) {
            out.add(placeEntry(lm.name(), "landmark"));
            added.add(lm.name());
        }
        // 地铁站
        for (MetroLine line : metroLines.values()) {
            for (String s : line.stations) {
                if (added.add(s) && (s.toLowerCase().contains(k) || k.contains(s.toLowerCase()))) {
                    out.add(placeEntry(s, "metro"));
                }
            }
        }
        // 地标模糊
        for (Landmark l : landmarks.values()) {
            if (added.add(l.name()) && (l.name().toLowerCase().contains(k) || k.contains(l.name().toLowerCase()))) {
                out.add(placeEntry(l.name(), "landmark"));
            }
        }
        // 公交站名（含在线路名里的也可作为地名）
        for (BusLine b : busLines.values()) {
            for (String s : b.stops()) {
                if (added.add(s) && (s.toLowerCase().contains(k) || k.contains(s.toLowerCase()))) {
                    out.add(placeEntry(s, "bus"));
                }
            }
        }
        return out;
    }

    private Map<String, Object> placeEntry(String name, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        return m;
    }

    // ================= 地铁两站路径（BFS 换乘） =================

    public record MetroPath(List<String> path, List<String> lines, int minutes, double km, double fare,
                            int transfers, String detail) {}

    /** 两站间最短时间路径：状态 = (站,当前线路)，边权 = 站间分钟 + 换乘 4 分钟 */
    public MetroPath metroPath(String from, String to) {
        String f = resolveMetro(from), t = resolveMetro(to);
        if (f == null || t == null) return null;
        if (f.equals(t)) {
            return new MetroPath(List.of(f), List.of(), 0, 0, 0, 0, "起终点相同，无需乘车");
        }
        // Dijkstra：状态 = (站, 到达该站所用线路)，边权 = 站间分钟 + 换乘 4 分钟
        Map<String, Double> dist = new ConcurrentHashMap<>();
        Map<String, String> prev = new ConcurrentHashMap<>(); // key -> 前驱 key
        java.util.PriorityQueue<Object[]> heap = new java.util.PriorityQueue<>(Comparator.comparingDouble(a -> (Double) a[0]));
        for (MetroLine line : metroLines.values()) {
            if (line.stations.contains(f)) {
                String key = f + "|" + line.id;
                dist.put(key, 0.0);
                heap.add(new Object[]{0.0, key, f, line.id});
            }
        }
        String bestKey = null;
        while (!heap.isEmpty()) {
            Object[] cur = heap.poll();
            double d = (Double) cur[0];
            String key = (String) cur[1];
            String st = (String) cur[2];
            String lineId = (String) cur[3];
            if (d > dist.getOrDefault(key, Double.MAX_VALUE)) continue;
            if (st.equals(t)) { bestKey = key; break; }
            MetroLine line = metroLines.get(lineId);
            int idx = line.stations.indexOf(st);
            int[] neighbors = {idx - 1, idx + 1};
            for (int ni : neighbors) {
                if (ni < 0 || ni >= line.stations.size()) continue;
                double step = line.perHopMinutes().get(Math.min(idx, ni));
                double nd = d + step;
                String nk = line.stations.get(ni) + "|" + lineId;
                if (nd < dist.getOrDefault(nk, Double.MAX_VALUE)) {
                    dist.put(nk, nd);
                    prev.put(nk, key);
                    heap.add(new Object[]{nd, nk, line.stations.get(ni), lineId});
                }
            }
            // 换乘：同站换线，+4 分钟
            for (MetroLine other : metroLines.values()) {
                if (other.id.equals(lineId) || !other.stations.contains(st)) continue;
                double nd = d + 4;
                String nk = st + "|" + other.id;
                if (nd < dist.getOrDefault(nk, Double.MAX_VALUE)) {
                    dist.put(nk, nd);
                    prev.put(nk, key);
                    heap.add(new Object[]{nd, nk, st, other.id});
                }
            }
        }
        if (bestKey == null) return null;
        // 回溯
        List<String> path = new ArrayList<>();
        List<String> usedLines = new ArrayList<>();
        String cur = bestKey;
        Set<String> visited = new HashSet<>();
        while (cur != null && visited.add(cur)) {
            String[] parts = cur.split("\\|");
            path.add(0, parts[0]);
            usedLines.add(0, parts[1]);
            cur = prev.get(cur);
        }
        // 压缩线路段
        double km = pathKm(path, usedLines);
        int minutes = (int) Math.round(dist.get(bestKey));
        double fare = metroFare(km);
        int transfers = 0;
        String lineSeq = usedLines.get(0);
        List<String> segLines = new ArrayList<>();
        String curLine = lineSeq;
        segLines.add(curLine);
        for (int i = 1; i < usedLines.size(); i++) {
            // usedLines 是「到达该节点的线路」，换乘发生在节点切换处
            if (!usedLines.get(i).equals(curLine)) {
                // 节点 i 是换乘站
                transfers++;
                curLine = usedLines.get(i);
                segLines.add(curLine);
            }
        }
        StringBuilder detail = new StringBuilder();
        String running = segLines.get(0);
        int segStart = 0;
        for (int i = 1; i <= path.size(); i++) {
            String l = i < path.size() ? usedLines.get(i) : "#END";
            if (i == path.size() || !l.equals(running)) {
                String fromS = path.get(segStart), toS = path.get(i - 1);
                double segKm = segKm(metroLines.get(running), fromS, toS);
                detail.append(metroLines.get(running).name())
                        .append("：").append(fromS).append(" → ").append(toS)
                        .append("（").append(String.format("%.1f", segKm)).append("km，")
                        .append(i - 1 - segStart).append(" 站）\n");
                if (i < path.size()) {
                    detail.append("  换乘 @ ").append(path.get(i - 1)).append("（步行+候车约 4 分钟）\n");
                }
                segStart = i - 1;
                running = i < path.size() ? l : running;
                if (i < path.size()) running = l;
            }
        }
        String brief = String.join(" → ", segLines.stream().map(id -> metroLines.get(id).name()).toList());
        return new MetroPath(path, segLines, minutes, round2(km), fare, transfers,
                brief + "\n" + detail + "票价 ¥" + fare + "（里程计价）");
    }

    private String resolveMetro(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        if (stationIndex.containsKey(n.toLowerCase())) return stationIndex.get(n.toLowerCase());
        // 地标 → 最近站
        Landmark l = landmarks.get(n.toLowerCase());
        if (l != null) return l.nearestStation();
        List<Map<String, Object>> hits = searchPlace(n);
        if (!hits.isEmpty()) {
            String hitName = String.valueOf(hits.get(0).get("name"));
            Landmark l2 = landmarks.get(hitName.toLowerCase());
            if (l2 != null) return l2.nearestStation();
            if (stationIndex.containsKey(hitName.toLowerCase())) return stationIndex.get(hitName.toLowerCase());
        }
        return null;
    }

    /** 路径总里程：按相邻两站所在线路的站间里程累加（换乘站跨线按 0.3km 步行估算） */
    private double pathKm(List<String> path, List<String> usedLines) {
        double km = 0;
        for (int i = 1; i < path.size(); i++) {
            String a = path.get(i - 1), b = path.get(i);
            String la = usedLines.get(i - 1), lb = usedLines.get(i);
            if (la.equals(lb)) {
                km += segKm(metroLines.get(la), a, b);
            } else {
                km += 0.3; // 同站换乘步行折算
            }
        }
        return km;
    }

    private double segKm(MetroLine line, String a, String b) {
        int ia = line.stations.indexOf(a), ib = line.stations.indexOf(b);
        if (ia < 0 || ib < 0) return 0.3;
        if (ia > ib) { int tmp = ia; ia = ib; ib = tmp; }
        double km = 0;
        for (int i = ia; i < ib; i++) km += line.km().get(i);
        return km;
    }

    /** 杭州地铁里程计价：0-4km ¥2；4-12km 每 4km +¥1；12-24km 每 6km +¥1；>24km 每 8km +¥1 */
    double metroFare(double km) {
        if (km <= 4) return 2;
        if (km <= 12) return 2 + (int) Math.ceil((km - 4) / 4);
        if (km <= 24) return 4 + (int) Math.ceil((km - 12) / 6);
        return 6 + (int) Math.ceil((km - 24) / 8);
    }

    // ================= 公交查询 =================

    public List<BusLine> busByStop(String stop) {
        if (stop == null || stop.isBlank()) return busLines();
        String k = stop.trim().toLowerCase();
        List<BusLine> out = new ArrayList<>();
        for (BusLine b : busLines.values()) {
            for (String s : b.stops()) {
                if (s.toLowerCase().contains(k) || k.contains(s.toLowerCase())) { out.add(b); break; }
            }
        }
        return out;
    }

    public BusLine busByCode(String code) {
        return busLines.get(code);
    }

    /** 按线路名模糊查：支持 "118" / "118路" / "B1" / "194 区间" 等写法 */
    public List<BusLine> busByLineName(String name) {
        String k = name.trim().toLowerCase().replace("路", "").replace("公交", "").trim();
        List<BusLine> out = new ArrayList<>();
        for (BusLine b : busLines.values()) {
            String n = b.name().toLowerCase().replace("路", "").trim();
            if (n.contains(k) || k.contains(n)) out.add(b);
        }
        return out;
    }

    // ================= 打车估算 =================

    public record TaxiQuote(String code, String label, double distanceKm, int minutes,
                            double fare, double surge, String breakdown, String carDesc) {}

    public TaxiQuote taxiQuote(String typeCode, double km, int minutes, boolean rushHour) {
        TaxiType t = taxiTypes.getOrDefault(typeCode, taxiTypes.get("eco"));
        double perKm = km > t.longTripFromKm() ? t.longTripPerKm() : t.perKm();
        double surge = rushHour ? 1.25 : 1.0;
        double raw = t.baseFare() + Math.max(0, km - t.baseKm()) * perKm + minutes * t.perMin();
        double fare = Math.round(raw * surge * 10) / 10.0;
        String breakdown = "起步价 ¥" + t.baseFare() + "（含 " + t.baseKm() + "km）"
                + " + 里程 " + String.format("%.1f", Math.max(0, km - t.baseKm())) + "km × ¥" + perKm
                + " + 时长 " + minutes + "min × ¥" + t.perMin()
                + (rushHour ? " × 高峰溢价 1.25" : "（平峰无溢价）")
                + " ≈ ¥" + fare;
        return new TaxiQuote(t.code(), t.label(), round2(km), minutes, fare, surge, breakdown, t.carDesc());
    }

    /** 市区平均车速 28km/h，高峰 19km/h */
    public static int driveMinutes(double km, boolean rushHour) {
        double speed = rushHour ? 19 : 28;
        return Math.max(4, (int) Math.round(km / speed * 60));
    }

    // ================= 核心：三方案规划 =================

    public record PlanOption(String tag, String label, String summary, int totalMinutes, double fare,
                             double walkMin, List<String> steps, String detail) {}

    public Map<String, Object> plan(String from, String to, String when) {
        boolean rush = isRush(when);
        List<PlanOption> options = new ArrayList<>();

        MetroPath mp = metroPath(from, to);
        if (mp != null && mp.minutes() > 0) {
            // 地铁步行接驳：起终点若是地标，加上步行分钟
            double walkA = landmarkWalkMin(from), walkB = landmarkWalkMin(to);
            double walk = walkA + walkB;
            int total = mp.minutes() + (int) Math.round(walk);
            List<String> steps = new ArrayList<>();
            if (walkA > 0) steps.add(String.format("步行 %.0f 分钟至「%s」地铁站", walkA, resolveMetro(from)));
            steps.addAll(List.of(mp.detail().split("\n")));
            if (walkB > 0) steps.add(String.format("出站步行 %.0f 分钟抵达「%s」", walkB, to));
            options.add(new PlanOption("fast", "🚇 地铁方案",
                    mp.path().get(0) + " → " + mp.path().get(mp.path().size() - 1)
                            + "，" + mp.minutes() + " 分钟，" + (mp.transfers() > 0 ? "换乘 " + mp.transfers() + " 次" : "无需换乘"),
                    total, mp.fare(), round2(walk), steps,
                    "总耗时约 " + total + " 分钟（乘车 " + mp.minutes() + " + 步行 " + (int) Math.round(walk) + "），"
                            + (rush ? "当前为高峰时段，地铁不受路况影响，时间最稳" : "平峰时段地铁准点率高")));
        }

        // 打车：直线距离 × 1.35 折算里程
        double straight = straightLineKm(from, to);
        if (straight > 0) {
            double km = round2(straight * 1.35);
            int driveMin = driveMinutes(km, rush);
            TaxiQuote eco = taxiQuote("eco", km, driveMin, rush);
            List<String> steps = List.of(
                    "上车点：导航至最近主干道（约 " + Math.max(1, (int) Math.round(3)) + " 分钟步行）",
                    "行程 " + km + "km，预计 " + driveMin + " 分钟（" + (rush ? "晚高峰均速 19km/h" : "平峰均速 28km/h") + "）",
                    eco.breakdown());
            options.add(new PlanOption("cheapest_possible", "🚕 打车直达",
                    eco.label() + "，" + driveMin + " 分钟，约 ¥" + eco.fare(),
                    driveMin + 4, eco.fare(), 4, steps,
                    "门到门最省心" + (rush ? "，但高峰易堵，时长波动 ±40%" : "，平峰时长可靠") + "。费用明细：" + eco.breakdown()));
        }

        // 公交直达（起终点都在同一条公交线路上的候选）
        PlanOption busOpt = busPlan(from, to, rush);
        if (busOpt != null) options.add(busOpt);

        // 排名：最快 = totalMinutes 最小；最便宜 = fare 最小；少步行 = walkMin 最小
        List<PlanOption> sorted = new ArrayList<>(options);
        PlanOption fastest = sorted.stream().min(Comparator.comparingInt(PlanOption::totalMinutes)).orElse(null);
        PlanOption cheapest = sorted.stream().min(Comparator.comparingDouble(PlanOption::fare)).orElse(null);
        PlanOption leastWalk = sorted.stream().min(Comparator.comparingDouble(PlanOption::walkMin)).orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("when", when == null || when.isBlank() ? "现在" : when);
        out.put("rushHour", rush);
        out.put("candidates", options);
        out.put("fastest", fastest == null ? null : fastest.tag());
        out.put("cheapest", cheapest == null ? null : cheapest.tag());
        out.put("leastWalk", leastWalk == null ? null : leastWalk.tag());
        return out;
    }

    private PlanOption busPlan(String from, String to, boolean rush) {
        // 出发地/目的地映射到公交站名（地标 → 邻近公交站固定映射表）
        String fStop = landmarkBusStop(from), tStop = landmarkBusStop(to);
        if (fStop == null || tStop == null || fStop.equals(tStop)) return null;
        for (BusLine b : busLines.values()) {
            int ia = b.stops().indexOf(fStop), ib = b.stops().indexOf(tStop);
            if (ia < 0 || ib < 0 || ia == ib) continue;
            int hopCount = Math.abs(ib - ia);
            int minutes = hopCount * 2 + 6; // 市区公交平均 2 分钟/站 + 起停
            double km = hopCount * 1.1;
            if (minutes > 75) continue; // 太慢的直达不推荐
            List<String> steps = List.of(
                    "步行 " + b.walkToStopMin() + " 分钟至「" + fStop + "」站",
                    "乘 " + b.name() + "（" + b.from() + " → " + b.to() + "），途经 " + (hopCount) + " 站约 " + minutes + " 分钟",
                    "运营时间 " + b.firstBus() + "—端点末班 " + b.lastBus() + "，班次间隔约 " + b.headwayMin() + " 分钟",
                    "在「" + tStop + "」下车，步行约 3 分钟抵达");
            return new PlanOption("bus", "🚌 公交直达",
                    b.name() + " 一线直达，" + minutes + " 分钟，票价 ¥" + String.format("%.0f", b.fare()),
                    minutes + (int) b.walkToStopMin() + 3, b.fare(), b.walkToStopMin() + 3, steps,
                    "最省钱的一线直达方案" + (rush ? "，高峰可能遇堵，实际时长 +10~20 分钟" : "") + "，适合不赶时间的出行");
        }
        return null;
    }

    private static final Map<String, String> LANDMARK_BUS = Map.ofEntries(
            Map.entry("西湖·断桥残雪", "断桥"),
            Map.entry("杭州东站（高铁枢纽）", "火车东站西"),
            Map.entry("武林商圈（杭州大厦/银泰）", "武林广场"),
            Map.entry("黄龙体育中心", "黄龙体育中心"),
            Map.entry("良渚博物院", "良渚博物馆"),
            Map.entry("湖滨银泰 in77", "东坡路平海路口"),
            Map.entry("临平银泰城", "临平大厦"),
            Map.entry("滨江区网易大厦", "江陵路地铁口"),
            Map.entry("浙大玉泉校区", "浙大公交站"),
            Map.entry("西溪湿地北门", "古荡"));

    private String landmarkBusStop(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase();
        String hit = LANDMARK_BUS.get(n);
        if (hit != null) return hit;
        // 直接是公交站名
        for (BusLine b : busLines.values()) {
            for (String s : b.stops()) {
                if (s.equalsIgnoreCase(name.trim())) return s;
            }
        }
        return null;
    }

    private double landmarkWalkMin(String name) {
        if (name == null) return 0;
        Landmark l = landmarks.get(name.trim().toLowerCase());
        return l != null ? l.walkMin() : 0;
    }

    /** 地名对之间的粗略直线距离：用两地在地铁线网中的索引近似（km），无坐标时退化为 0 */
    private double straightLineKm(String from, String to) {
        double[] a = coordOf(from), b = coordOf(to);
        if (a == null || b == null) return 0;
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /** 每站一个近似坐标（视觉线路图同源，这里只用于打车距离估算），单位 km */
    private double[] coordOf(String name) {
        String n = name.trim().toLowerCase();
        // 先查地标
        for (Landmark l : landmarks.values()) {
            if (l.name().toLowerCase().equals(n)) {
                return stationCoord(l.nearestStation());
            }
        }
        return stationCoord(name);
    }

    private double[] stationCoord(String station) {
        String s = station == null ? "" : station.trim().toLowerCase();
        for (MetroLine line : metroLines.values()) {
            int idx = line.stations.indexOf(stationIndex.getOrDefault(s, ""));
            if (idx >= 0) {
                // 线路铺开坐标：1 号线横向、2 号线斜向、10 号线纵向，三线交汇于市区
                return switch (line.id) {
                    case "m1" -> new double[]{1.5 + idx * 2.1, 8 - Math.abs(idx - 9) * 0.2};
                    case "m2" -> new double[]{2.5 + idx * 1.7, 3 + idx * 0.9};
                    default -> new double[]{10 + idx * 0.4, 4 + idx * 1.6};
                };
            }
        }
        return null;
    }

    private static boolean isRush(String when) {
        if (when == null || when.isBlank() || when.equals("现在")) {
            int h = java.time.LocalTime.now().getHour();
            return (h >= 7 && h < 10) || (h >= 16 && h < 19);
        }
        java.time.LocalDateTime t;
        try {
            t = java.time.LocalDateTime.parse(when);
        } catch (Exception e) {
            try {
                t = java.time.LocalTime.parse(when).atDate(java.time.LocalDate.now());
            } catch (Exception e2) {
                return false;
            }
        }
        int h = t.getHour();
        return (h >= 7 && h < 10) || (h >= 16 && h < 19);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
