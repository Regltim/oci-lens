package org.ociarmmonitor.serverstatus;

public record HostDisk(String mount, Long totalBytes, Long usedBytes, Long usableBytes, Double usagePercent) {
}
