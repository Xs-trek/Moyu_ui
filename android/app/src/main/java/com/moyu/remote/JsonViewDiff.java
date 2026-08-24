package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Produces the bounded set/remove patch understood by the bundled Web UI. */
final class JsonViewDiff {
    private JsonViewDiff() { }

    static JSONArray between(JSONObject before, JSONObject after, int maxOperations) {
        if (before == null || after == null || maxOperations < 1) return null;
        JSONArray operations = new JSONArray();
        return diff(before, after, "", operations, maxOperations) ? operations : null;
    }

    private static boolean diff(Object before, Object after, String path, JSONArray operations, int maximum) {
        if (before instanceof JSONObject && after instanceof JSONObject) {
            JSONObject left = (JSONObject) before;
            JSONObject right = (JSONObject) after;
            Set<String> keys = new TreeSet<>();
            left.keys().forEachRemaining(keys::add);
            right.keys().forEachRemaining(keys::add);
            for (String key : keys) {
                String childPath = path + "/" + escape(key);
                boolean hasLeft = left.has(key);
                boolean hasRight = right.has(key);
                if (!hasRight) {
                    try {
                        if (!add(operations, new JSONObject().put("op", "remove").put("path", childPath), maximum)) return false;
                    } catch (Exception invalid) { return false; }
                } else if (!hasLeft) {
                    if (!set(operations, childPath, right.opt(key), maximum)) return false;
                } else if (!diff(left.opt(key), right.opt(key), childPath, operations, maximum)) return false;
            }
            return true;
        }
        if (before instanceof JSONArray && after instanceof JSONArray) {
            JSONArray left = (JSONArray) before;
            JSONArray right = (JSONArray) after;
            if (left.length() != right.length()) return set(operations, path, right, maximum);
            for (int index = 0; index < right.length(); index++) {
                if (!diff(left.opt(index), right.opt(index), path + "/" + index, operations, maximum)) return false;
            }
            return true;
        }
        if (sameScalar(before, after)) return true;
        return set(operations, path, after, maximum);
    }

    private static boolean set(JSONArray operations, String path, Object value, int maximum) {
        try {
            return add(operations, new JSONObject().put("op", "set").put("path", path)
                    .put("value", value == null ? JSONObject.NULL : value), maximum);
        } catch (Exception invalid) { return false; }
    }

    private static boolean add(JSONArray operations, JSONObject operation, int maximum) {
        if (operations.length() >= maximum) return false;
        operations.put(operation);
        return true;
    }

    private static boolean sameScalar(Object left, Object right) {
        if (left == JSONObject.NULL) left = null;
        if (right == JSONObject.NULL) right = null;
        if (left instanceof Number && right instanceof Number) return left.toString().equals(right.toString());
        return Objects.equals(left, right);
    }

    private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
}
