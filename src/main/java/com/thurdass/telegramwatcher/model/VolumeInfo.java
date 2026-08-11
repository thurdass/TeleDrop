package com.thurdass.telegramwatcher.model;

public record VolumeInfo(int number, String name, long sizeBytes, String sha256, boolean uploaded) {
}
