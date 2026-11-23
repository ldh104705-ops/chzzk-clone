package com.chzzk.live;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Single-instance coordinator: all state in this process and delivery is a plain method call. */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "local", matchIfMissing = true)
class LocalLiveCoordinator implements LiveCoordinator {

    private final Map<Long, String> broadcasters = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> viewers = new ConcurrentHashMap<>();
    private volatile Consumer<Envelope> delivery = e -> { };

    @Override
    public void subscribe(Consumer<Envelope> localDelivery) {
        this.delivery = localDelivery;
    }

    @Override
    public void send(Envelope envelope) {
        delivery.accept(envelope);
    }

    @Override
    public void broadcasterJoined(long broadcastId, String token) {
        broadcasters.put(broadcastId, token);
    }

    @Override
    public void broadcasterHeartbeat(long broadcastId, String token) {
        // in-process presence cannot expire
    }

    @Override
    public void broadcasterLeft(long broadcastId, String token) {
        broadcasters.remove(broadcastId, token);
    }

    @Override
    public boolean broadcasterPresent(long broadcastId) {
        return broadcasters.containsKey(broadcastId);
    }

    @Override
    public void viewerJoined(long broadcastId, String viewerId) {
        viewerSet(broadcastId).add(viewerId);
    }

    @Override
    public void viewerHeartbeat(long broadcastId, String viewerId) {
        // in-process presence cannot expire
    }

    @Override
    public void viewerLeft(long broadcastId, String viewerId) {
        viewerSet(broadcastId).remove(viewerId);
    }

    @Override
    public List<String> viewers(long broadcastId) {
        Set<String> set = viewerSet(broadcastId);
        synchronized (set) {
            return new ArrayList<>(set);
        }
    }

    @Override
    public boolean hasViewer(long broadcastId, String viewerId) {
        return viewerSet(broadcastId).contains(viewerId);
    }

    @Override
    public void clear(long broadcastId) {
        broadcasters.remove(broadcastId);
        viewers.remove(broadcastId);
    }

    private Set<String> viewerSet(long broadcastId) {
        return viewers.computeIfAbsent(broadcastId, k -> java.util.Collections.synchronizedSet(new LinkedHashSet<>()));
    }
}
