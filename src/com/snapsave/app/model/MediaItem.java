package com.snapsave.app.model;

/** A resolved media page (video/audio) shown in lists and detail screens. */
public class MediaItem {
    public String id;
    public String title;
    public String author;
    public String duration;   // "12:48"
    public String meta;       // "856K views"
    public int grad;          // gradient index 0..3
    public String pageUrl;    // canonical page url
    public String videoId;    // youtube id if any
    public String thumbUrl;   // preview image url

    public MediaItem() {
    }

    public MediaItem(String title, String author, String duration, String meta, int grad, String pageUrl) {
        this.title = title;
        this.author = author;
        this.duration = duration;
        this.meta = meta;
        this.grad = grad;
        this.pageUrl = pageUrl;
        this.id = Integer.toHexString((title + pageUrl).hashCode());
    }
}
