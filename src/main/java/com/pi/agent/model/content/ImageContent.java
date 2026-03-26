package com.pi.agent.model.content;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Image content block.
 */
public record ImageContent(
    @JsonProperty("type") String type,
    @JsonProperty("url") String url,
    @JsonProperty("mimeType") String mimeType,
    @JsonProperty("base64") String base64
) implements Content {
    
    public ImageContent(String url, String mimeType, String base64) {
        this("image", url, mimeType, base64);
    }
    
    public ImageContent(String url, String mimeType) {
        this(url, mimeType, null);
    }
    
    public ImageContent(String url) {
        this(url, null, null);
    }
    
    public static ImageContent ofUrl(String url, String mimeType) {
        return new ImageContent(url, mimeType);
    }
    
    public static ImageContent ofBase64(String base64, String mimeType) {
        return new ImageContent(null, mimeType, base64);
    }
}
