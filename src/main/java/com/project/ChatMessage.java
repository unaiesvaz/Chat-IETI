package com.project;

public final class ChatMessage {
    private final String role;
    private final String text;
    private final byte[] imageBytes;
    private final String imageMimeType;

    public ChatMessage(String role, String text, byte[] imageBytes, String imageMimeType) {
        this.role = role;
        this.text = text == null ? "" : text;
        this.imageBytes = imageBytes == null ? null : imageBytes.clone();
        this.imageMimeType = imageMimeType;
    }

    public String getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    public byte[] getImageBytes() {
        return imageBytes == null ? null : imageBytes.clone();
    }

    public String getImageMimeType() {
        return imageMimeType;
    }


    public boolean hasImage() {
        return imageBytes != null;
    }
}