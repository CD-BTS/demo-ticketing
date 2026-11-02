package com.bts.demoticketing.user.entity;

// Domain entity mapped by DemoUserRepository; never returned as an API response.
public record DemoUser(long id, String publicId, String serviceId, String externalId, String btsUserId) {}
