package com.brave.adblock;

import android.net.Uri;
import android.webkit.WebResourceRequest;

public class AdBlockUtils {
    public static AdBlockClient.FilterOption mapRequestToFilterOption(WebResourceRequest webResourceRequest) {
        if (webResourceRequest.isForMainFrame()) {
            return AdBlockClient.FilterOption.DOCUMENT;
        }

        String accept = webResourceRequest.getRequestHeaders().get("Accept");
        String xhr = webResourceRequest.getRequestHeaders().get("X-Requested-With");
        Uri url = webResourceRequest.getUrl();

        if (xhr != null || (accept != null && accept.contains("json"))) {
            return AdBlockClient.FilterOption.XHR;
        }

        if (accept != null) {
            if (accept.contains("text/html")) return AdBlockClient.FilterOption.SUBDOCUMENT;
            if (accept.contains("image/")) return AdBlockClient.FilterOption.IMAGE;
            if (accept.contains("/css")) return AdBlockClient.FilterOption.CSS;
            if (accept.contains("javascript")) return AdBlockClient.FilterOption.SCRIPT;
            if (accept.contains("video/") || accept.contains("audio/")) return AdBlockClient.FilterOption.OBJECT;
        }

        if (url != null) {
            if (uriHasExtension(url, "css")) return AdBlockClient.FilterOption.CSS;
            if (uriHasExtension(url, "js")) return AdBlockClient.FilterOption.SCRIPT;
            if (uriHasExtension(url, "png", "jpg", "jpeg", "webp", "svg", "gif", "bmp", "tiff")) return AdBlockClient.FilterOption.IMAGE;
            if (uriHasExtension(url, "mp4", "mov", "avi", "m3u8", "ts")) return AdBlockClient.FilterOption.OBJECT;
        }

        if (accept != null && accept.contains("*/*")) {
            return AdBlockClient.FilterOption.XHR;
        }

        return AdBlockClient.FilterOption.UNKNOWN;
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