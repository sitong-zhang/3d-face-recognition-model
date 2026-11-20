package com.example.face3d;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

/**
 * 本地人脸库（自研数据层）。
 *
 * 以 JSON 存储多个人脸档案：每个人有「姓名 + 多个角度的 3D 几何特征」。
 * 使用 Android 内置的 org.json 与 SharedPreferences，无额外依赖。
 */
public class FaceStore {

    public static class Person {
        public final String name;
        public final List<float[]> features; // 多角度 3D 特征
        Person(String name, List<float[]> features) {
            this.name = name;
            this.features = features;
        }
    }

    /** 匹配结果：姓名 + 最佳相似度。 */
    public static class Match {
        public final String name;
        public final float similarity;
        Match(String name, float similarity) {
            this.name = name;
            this.similarity = similarity;
        }
    }

    private static final String PREF = "face3d_db";
    private static final String KEY = "persons_json";
    private final SharedPreferences prefs;

    public FaceStore(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 读取全部人脸档案。 */
    public List<Person> load() {
        List<Person> out = new ArrayList<>();
        String json = prefs.getString(KEY, null);
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.getString("name");
                JSONArray fs = o.getJSONArray("features");
                List<float[]> feats = new ArrayList<>();
                for (int j = 0; j < fs.length(); j++) {
                    String s = fs.getString(j);
                    if (!s.isEmpty()) feats.add(decode(s));
                }
                out.add(new Person(name, feats));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 覆盖写入全部。 */
    private void save(List<Person> persons) {
        try {
            JSONArray arr = new JSONArray();
            for (Person p : persons) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                JSONArray fs = new JSONArray();
                for (float[] f : p.features) fs.put(encode(f));
                o.put("features", fs);
                arr.put(o);
            }
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** 新增或更新一个人（同名覆盖）。 */
    public void upsert(String name, List<float[]> features) {
        List<Person> all = load();
        all.removeIf(p -> p.name.equals(name));
        all.add(new Person(name, features));
        save(all);
    }

    public void delete(String name) {
        List<Person> all = load();
        all.removeIf(p -> p.name.equals(name));
        save(all);
    }

    public int count() {
        return load().size();
    }

    /**
     * 与库中所有人脸比对，返回相似度最高者。
     * 每个人取「所有角度特征」中的最高相似度作为该人的得分。
     */
    public Match findBest(float[] probe) {
        Match best = null;
        for (Person p : load()) {
            float bestForPerson = 0f;
            for (float[] f : p.features) {
                float sim = FaceEngine.similarity(probe, f);
                if (sim > bestForPerson) bestForPerson = sim;
            }
            if (best == null || bestForPerson > best.similarity) {
                best = new Match(p.name, bestForPerson);
            }
        }
        return best;
    }

    private static String encode(float[] f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.5f", f[i]));
        }
        return sb.toString();
    }

    private static float[] decode(String s) {
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
