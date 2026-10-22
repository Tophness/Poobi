package com.brave.adblock;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import java.util.Map;

public class AdBlockUtils {

    public static AdBlockClient.FilterOption mapRequestToFilterOption(WebResourceRequest webResourceRequest) {
        if (webResourceRequest.isForMainFrame()) {
            return AdBlockClient.FilterOption.DOCUMENT;
        }

        Map<String, String> headers = webResourceRequest.getRequestHeaders();
        String secFetchDest = getHeaderCaseInsensitive(headers, "sec-fetch-dest");
        String accept = getHeaderCaseInsensitive(headers, "accept");
        String xhr = getHeaderCaseInsensitive(headers, "x-requested-with");
        String method = webResourceRequest.getMethod();
        Uri url = webResourceRequest.getUrl();

        if (secFetchDest != null && !secFetchDest.isEmpty()) {
            switch (secFetchDest.toLowerCase()) {
                case "script":
                    return AdBlockClient.FilterOption.SCRIPT;
                case "image":
                    return AdBlockClient.FilterOption.IMAGE;
                case "style":
                    return AdBlockClient.FilterOption.CSS;
                case "iframe":
                case "frame":
                    return AdBlockClient.FilterOption.SUBDOCUMENT;
                case "document":
                    return AdBlockClient.FilterOption.DOCUMENT;
                case "video":
                case "audio":
                    return AdBlockClient.FilterOption.OBJECT;
                case "empty":
                    return AdBlockClient.FilterOption.XHR;
                default:
                    break;
            }
        }

        if (xhr != null || (accept != null && accept.contains("json")) ||
            "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            return AdBlockClient.FilterOption.XHR;
        }

        if (accept != null) {
            String lowerAccept = accept.toLowerCase();
            if (lowerAccept.contains("text/html")) return AdBlockClient.FilterOption.SUBDOCUMENT;
            if (lowerAccept.contains("image/")) return AdBlockClient.FilterOption.IMAGE;
            if (lowerAccept.contains("/css")) return AdBlockClient.FilterOption.CSS;
            if (lowerAccept.contains("javascript") || lowerAccept.contains("ecmascript")) return AdBlockClient.FilterOption.SCRIPT;
            if (lowerAccept.contains("video/") || lowerAccept.contains("audio/")) return AdBlockClient.FilterOption.OBJECT;
        }

        if (url != null) {
            if (uriHasExtension(url, "css")) return AdBlockClient.FilterOption.CSS;
            if (uriHasExtension(url, "js", "mjs")) return AdBlockClient.FilterOption.SCRIPT;
            if (uriHasExtension(url, "png", "jpg", "jpeg", "webp", "svg", "gif", "bmp", "ico", "tiff")) return AdBlockClient.FilterOption.IMAGE;
            if (uriHasExtension(url, "mp4", "mov", "avi", "m3u8", "ts", "webm", "mpd", "m4s")) return AdBlockClient.FilterOption.OBJECT;

            String path = url.getPath();
            if (path != null) {
                String lowerPath = path.toLowerCase();
                if (lowerPath.contains("/pagead") || lowerPath.contains("ads.js") || lowerPath.contains("/gtag") || lowerPath.contains("/analytics")) {
                    return AdBlockClient.FilterOption.SCRIPT;
                }
            }
        }

        if (accept != null && accept.contains("*/*")) {
            return AdBlockClient.FilterOption.XHR;
        }

        return AdBlockClient.FilterOption.OTHER;
    }

    private static String getHeaderCaseInsensitive(Map<String, String> headers, String targetKey) {
        if (headers == null || headers.isEmpty() || targetKey == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (targetKey.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static boolean uriHasExtension(Uri uri, String... strArr) {
        String path = uri.getPath();
        if (path == null) return false;
        String lowerPath = path.toLowerCase();
        for (String str : strArr) {
            if (lowerPath.endsWith("." + str.toLowerCase())) return true;
        }
        return false;
    }
}