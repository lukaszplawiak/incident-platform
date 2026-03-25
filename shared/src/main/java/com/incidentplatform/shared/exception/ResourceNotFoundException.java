package com.incidentplatform.shared.exception;

import java.util.UUID;

public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;

    private final String resourceId;

    public ResourceNotFoundException(String resourceType, UUID resourceId) {
        super(String.format("%s with id '%s' was not found",
                resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = resourceId.toString();
    }

    public ResourceNotFoundException(String resourceType, String resourceId) {
        super(String.format("%s with id '%s' was not found",
                resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public ResourceNotFoundException(String message) {
        super(message);
        this.resourceType = "Unknown";
        this.resourceId = "Unknown";
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }
}