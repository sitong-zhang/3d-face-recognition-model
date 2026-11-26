package com.example.face3d;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

/**
 * 本地 3D 人脸库（自研数据层）。
 *
 * 与 Glance 只保存 512D 向量、丢弃其余数据不同，这里为每个人保存**完整的 3D 人脸资产**：
 * - embeddings：多角度 512D 身份特征（MobileFaceNet ArcFace，判别身份）
 * - geometries：多角度 478×3 归一化 3D 几何（鼻尖为原点、眼距为尺度的完整 3D 结构）
 * - mesh3d：用于独立查看的 478 点原始 3D 网格
 *
 * 每次识别都用「512D 身份 + 3D 几何」联合比对，两者同意才判定为同一人。
 */
public class FaceStore {

    /**
     * 身份余弦距离阈值（1 - 余弦相似度）。
     * 端到端标定（e2e_enroll_scan_test.py，多朝向转正后）：
     *   本人最近距离 0.00~0.40，库外人最近距离 0.88~0.98，中间空隙大。
     * 取 0.65 落在最优区间中部：本人 100% 接受 + 库外人 100% 拒绝。
     * （旧值 1.05 过宽，会把所有陌生人误判为库中人。）
     */
    public static final float DIST_THRESHOLD = 0.65f;
    /** 3D 几何距离阈值（归一化特征的平均欧氏距离），实测同人/异人区分留有余量。 */
    public static final float GEO_THRESHOLD = 0.42f;

    public static class Person {
        public final String name;
        public final List<float[]> embeddings;  // 多角度 512D 身份特征
        public final List<float[]> geometries;  // 多角度 478*3 归一化 3D 几何（3D 资产）
        public final float[] mesh3d;            // 478 点原始 3D 网格（查看用）
        Person(String name, List<float[]> embeddings, List<float[]> geometries, float[] mesh3d) {
            this.name = name;
            this.embeddings = embeddings;
            this.geometries = geometries;
            this.mesh3d = mesh3d;
        }
    }

    /** 匹配结果：姓名 + 身份距离 + 3D 几何距离 + 融合置信度（0..1）。 */
    public static class Match {
        public final String name;
        public final float distance;      // 身份余弦距离（兼容既有显示）
        public final float geoDistance;   // 3D 几何距离
        public final float confidence;    // 融合置信度
        Match(String name, float distance, float geoDistance, float confidence) {
            this.name = name;
            this.distance = distance;
            this.geoDistance = geoDistance;
            this.confidence = confidence;
        }
    }

    private static final String PREF = "face3d_db_v3";  // v3：新增 3D 几何资产
    private static final String KEY = "persons_json";
    private final SharedPreferences prefs;

    public FaceStore(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Person> load() {
        List<Person> out = new ArrayList<>();
        String json = prefs.getString(KEY, null);
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.getString("name");
                List<float[]> feats = decodeList(o.optJSONArray("embeddings"), 5);
                List<float[]> geos = decodeList(o.optJSONArray("geometries"), 4);
                float[] mesh = o.has("mesh3d") ? decode(o.getString("mesh3d"), 5) : new float[0];
                if (!feats.isEmpty()) out.add(new Person(name, feats, geos, mesh));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void save(List<Person> persons) {
        try {
            JSONArray arr = new JSONArray();
            for (Person p : persons) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("embeddings", encodeList(p.embeddings, 5));
                if (p.geometries != null && !p.geometries.isEmpty()) {
                    o.put("geometries", encodeList(p.geometries, 4));
                }
                if (p.mesh3d != null && p.mesh3d.length > 0) o.put("mesh3d", encode(p.mesh3d, 5));
                arr.put(o);
            }
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    public void upsert(String name, List<float[]> embeddings, List<float[]> geometries, float[] mesh3d) {
        List<Person> all = load();
        all.removeIf(p -> p.name.equals(name));
        all.add(new Person(name, embeddings, geometries, mesh3d));
        save(all);
    }

    public void delete(String name) {
        List<Person> all = load();
        all.removeIf(p -> p.name.equals(name));
        save(all);
    }

    /**
     * 老数据兼容用的简单多帧平均（回退方案）。
     *
     * 新录入的人 mesh3d 存的是端侧高斯重建管线的融合结果，查看器直接用它即可。
     * 本方法只服务早期没有重建 mesh 的老数据：把多帧几何简单平均出一个可用 mesh。
     * depthGain 保持 1（不做任何深度放大）——人为放大 z 会让鼻子变尖锥、几何失真。
     *
     * @param depthGain 深度系数，必须为 1（不放大）
     * @return 478*3 融合几何；无几何资产时返回 null
     */
    public static float[] fusedGeometry(Person p, float depthGain) {
        if (p == null || p.geometries == null || p.geometries.isEmpty()) return null;
        final int N = 478;
        float[] sum = new float[N * 3];
        int cnt = 0;
        for (float[] g : p.geometries) {
            if (g == null || g.length < N * 3) continue;
            for (int i = 0; i < N * 3; i++) sum[i] += g[i];
            cnt++;
        }
        if (cnt == 0) return null;
        float[] out = new float[N * 3];
        float inv = 1f / cnt;
        for (int i = 0; i < N; i++) {
            out[i * 3]     = sum[i * 3] * inv;
            out[i * 3 + 1] = sum[i * 3 + 1] * inv;
            out[i * 3 + 2] = sum[i * 3 + 2] * inv * depthGain; // 老数据回退，depthGain=1 不放大
        }
        return out;
    }

    public int count() { return load().size(); }

    /**
     * 3D 人脸联合比对：对库中每个人，取「多角度身份特征」的最小余弦距离，
     * 与「多角度 3D 几何资产」的最小归一化欧氏距离，融合得到置信度。
     */
    public Match findBest(float[] probeEmb, float[] probeGeo) {
        if (probeEmb == null) return null;
        Match best = null;
        for (Person p : load()) {
            float bestEmb = Float.MAX_VALUE;
            for (float[] f : p.embeddings) {
                float d = FaceEmbedder.distance(probeEmb, f);
                if (d < bestEmb) bestEmb = d;
            }
            float bestGeo = Float.MAX_VALUE;
            if (probeGeo != null && p.geometries != null) {
                for (float[] g : p.geometries) {
                    float d = geoDistance(probeGeo, g);
                    if (d < bestGeo) bestGeo = d;
                }
            }
            if (bestGeo == Float.MAX_VALUE) bestGeo = GEO_THRESHOLD; // 无几何资产时中性处理
            float conf = confidence(bestEmb, bestGeo);
            if (best == null || conf > best.confidence) {
                best = new Match(p.name, bestEmb, bestGeo, conf);
            }
        }
        return best;
    }

    /** 兼容旧调用（仅身份比对）。 */
    public Match findBest(float[] probeEmb) { return findBest(probeEmb, null); }

    /**
     * 识别最终判定：只跟身份投票选出的那个人比，用【重建后的完整 3D 几何】做硬比对。
     * 重建 mesh 与库中 geometries 同坐标系（鼻尖为原点、眼距为尺度的归一化 3D）。
     * 这样"扫别人也能过"不成立——别人的重建几何与该人库内几何距离远超 GEO_THRESHOLD。
     */
    public Match findBestWithGeometry(String candidateName, float[] fusedMesh, float embDistance) {
        if (candidateName == null || fusedMesh == null) return null;
        for (Person p : load()) {
            if (!p.name.equals(candidateName)) continue;
            float bestGeo = Float.MAX_VALUE;
            if (p.geometries != null) {
                for (float[] g : p.geometries) {
                    float d = geoDistance(fusedMesh, g);
                    if (d < bestGeo) bestGeo = d;
                }
            }
            if (bestGeo == Float.MAX_VALUE) bestGeo = GEO_THRESHOLD + 1f; // 无几何资产直接判失败
            return new Match(p.name, embDistance, bestGeo, confidence(embDistance, bestGeo));
        }
        return null;
    }

    /** 融合置信度：身份占主导(0.8)，3D 几何参与(0.2)，均映射到 0..1。 */
    private static float confidence(float embDist, float geoDist) {
        float embScore = clamp01(1f - embDist / (DIST_THRESHOLD * 1.6f));
        float geoScore = clamp01(1f - geoDist / (GEO_THRESHOLD * 2.2f));
        return 0.8f * embScore + 0.2f * geoScore;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** 两个归一化 3D 几何特征的平均欧氏距离。 */
    public static float geoDistance(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return Float.MAX_VALUE;
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum / a.length);
    }

    // ---------------- 序列化工具 ----------------

    private static JSONArray encodeList(List<float[]> list, int decimals) {
        JSONArray arr = new JSONArray();
        for (float[] f : list) arr.put(encode(f, decimals));
        return arr;
    }

    private static List<float[]> decodeList(JSONArray arr, int decimals) {
        List<float[]> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            float[] f = decode(arr.optString(i), decimals);
            if (f.length > 0) out.add(f);
        }
        return out;
    }

    private static String encode(float[] f, int decimals) {
        StringBuilder sb = new StringBuilder();
        String fmt = "%." + decimals + "f";
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, fmt, f[i]));
        }
        return sb.toString();
    }

    private static float[] decode(String s, int decimals) {
        if (s == null || s.isEmpty()) return new float[0];
        String[] parts = s.split(",");
        float[] f = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) f[i] = Float.parseFloat(parts[i]);
        } catch (Exception ignored) {
            return new float[0];
        }
        return f;
    }
}
