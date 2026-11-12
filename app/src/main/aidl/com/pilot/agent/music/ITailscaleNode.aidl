package com.pilot.agent.music;

interface ITailscaleNode {
    String start(String stateDir, String snapshot);
    String status(String snapshot);
    String get(String url, String cookie, long timeoutMillis, String snapshot);
    String stop();
}
