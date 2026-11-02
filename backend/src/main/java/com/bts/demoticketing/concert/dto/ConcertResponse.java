package com.bts.demoticketing.concert.dto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import com.bts.demoticketing.concert.entity.Concert;

public record ConcertResponse(String id, String title, String artist, String venue,
        OffsetDateTime startAt, long priceFrom, String tagline, int hue) {
    public static ConcertResponse from(Concert concert) {
        return new ConcertResponse(concert.getPublicId(), concert.getTitle(), concert.getArtist(),
                concert.getVenue(), concert.getStartAt().atOffset(ZoneOffset.UTC),
                concert.getPriceFrom(), concert.getTagline(), concert.getHue());
    }
}
