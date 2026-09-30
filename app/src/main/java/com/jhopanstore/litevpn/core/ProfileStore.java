package com.jhopanstore.litevpn.core;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-profile store: list of VLESS configs saved as JSON in prefs.
 * Profiles are shown as swipeable pages (ViewPager2). Active profile is what CONNECT uses.
 */
public final class ProfileStore {
    public static final class Profile {
        public final String id;
        public final String name;
        public final String address;
        public final String uuid;
        public final String path;
        public final String sni;
        public final String host;

        public Profile(String id, String name, String address, String uuid, String path, String sni, String host) {
            this.id = id; this.name = name; this.address = address; this.uuid = uuid;
            this.path = path; this.sni = sni; this.host = host;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.putOpt("id", id); o.putOpt("name", name); o.putOpt("address", address);
                o.putOpt("uuid", uuid); o.putOpt("path", path); o.putOpt("sni", sni); o.putOpt("host", host);
            } catch (Exception ignored) {}
            return o;
        }

        static Profile fromJson(JSONObject o) {
            return new Profile(
                o.optString("id"), o.optString("name"),
                o.optString("address"), o.optString("uuid"), o.optString("path"),
                o.optString("sni"), o.optString("host"));
        }
    }

    private static final int MAX_PROFILES = 10;
    private final SharedPreferences prefs;

    public ProfileStore(SharedPreferences prefs) { this.prefs = prefs; }

    public synchronized List<Profile> all() {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("profiles", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Profile.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized int count() { return all().size(); }

    public synchronized Profile get(String id) {
        for (Profile p : all()) if (p.id.equals(id)) return p;
        return null;
    }

    public synchronized Profile active() {
        List<Profile> list = all();
        String activeId = prefs.getString("active_profile", null);
        if (activeId != null) for (Profile p : list) if (p.id.equals(activeId)) return p;
        return list.isEmpty() ? null : list.get(0);
    }

    /** Insert or update by id. Returns false when limit reached. */
    public synchronized boolean put(Profile profile) {
        List<Profile> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(profile.id)) { list.set(i, profile); save(list); return true; }
        }
        if (list.size() >= MAX_PROFILES) return false;
        list.add(profile);
        save(list);
        return true;
    }

    public synchronized void remove(String id) {
        List<Profile> list = all();
        List<Profile> kept = new ArrayList<>();
        int removedAt = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) { if (removedAt < 0) removedAt = i; continue; }
            kept.add(list.get(i));
        }
        if (removedAt < 0) return; // nothing matched: leave prefs untouched
        save(kept);
        if (id.equals(prefs.getString("active_profile", null))) setActiveId(nextActiveId(kept, removedAt));
    }

    /**
     * Pick the next active profile after a removal: nearest surviving neighbour first, but prefer a
     * neighbour that actually holds a config so the user does not land on an empty profile and lose
     * the ability to connect.
     */
    private static String nextActiveId(List<Profile> kept, int removedAt) {
        if (kept.isEmpty()) return null;
        int nearest = Math.max(0, Math.min(removedAt, kept.size() - 1));
        for (int offset = 0; offset < kept.size(); offset++) {
            int before = nearest - offset;
            if (before >= 0 && hasConfig(kept.get(before))) return kept.get(before).id;
            int after = nearest + offset;
            if (after < kept.size() && hasConfig(kept.get(after))) return kept.get(after).id;
        }
        return kept.get(nearest).id;
    }

    public static boolean hasConfig(Profile profile) {
        return profile != null && profile.address != null && !profile.address.trim().isEmpty();
    }

    public synchronized int countWithConfig() {
        int total = 0;
        for (Profile profile : all()) if (hasConfig(profile)) total++;
        return total;
    }

    public void setActiveId(String id) { prefs.edit().putString("active_profile", id).apply(); }

    private void save(List<Profile> list) {
        JSONArray arr = new JSONArray();
        for (Profile p : list) arr.put(p.toJson());
        prefs.edit().putString("profiles", arr.toString()).apply();
    }

    public static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 900) + 100, 36);
    }

    /** Ensure at least one empty profile exists (first run). Returns active profile. */
    public Profile ensureDefault() {
        if (count() == 0) {
            Profile def = new Profile(newId(), "Default", "", "", "/", "", "");
            put(def);
        }
        Profile a = active();
        if (a != null) setActiveId(a.id);
        return a;
    }
}
