package com.bts.demoticketing.concert.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;

@Entity
@Table(name = "concerts")
public class Concert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 50)
    private String publicId;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String artist;
    @Column(nullable = false)
    private String venue;
    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;
    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;
    @Column(name = "price_from", nullable = false)
    private long priceFrom;
    @Column(nullable = false)
    private String tagline;
    @Column(nullable = false)
    private int hue;

    protected Concert() {}
    public String getPublicId() { return publicId; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getVenue() { return venue; }
    public LocalDateTime getStartAt() { return startAt; }
    public long getPriceFrom() { return priceFrom; }
    public String getTagline() { return tagline; }
    public int getHue() { return hue; }
}
