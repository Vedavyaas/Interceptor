package com.pheonix.interceptor.model;

/**
 * Immutable point-in-time snapshot of all host-level metrics.
 * Produced by the background collector in {@code SystemMetricsService}
 * and consumed by the AOP layer via a single {@code AtomicReference.get()}.
 */
public class HostMetrics {

    private final Compute compute;
    private final Memory  memory;
    private final Network network;
    private final Disk    disk;

    public HostMetrics(Compute compute, Memory memory, Network network, Disk disk) {
        this.compute = compute;
        this.memory  = memory;
        this.network = network;
        this.disk    = disk;
    }

    public Compute getCompute() { return compute; }
    public Memory  getMemory()  { return memory;  }
    public Network getNetwork() { return network; }
    public Disk    getDisk()    { return disk;    }
}
