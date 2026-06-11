package com.pheonix.interceptor.model;

public class EventPayload {

    private String eventId;
    private Company company;
    private Agent agent;
    private Resource resource;
    private Compute compute;
    private Memory memory;
    private Network network;
    private Disk disk;
    private Database database;
    private Application application;

    // ── Getters & Setters ────────────────────────────────────────────────────

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }

    public Agent getAgent() { return agent; }
    public void setAgent(Agent agent) { this.agent = agent; }

    public Resource getResource() { return resource; }
    public void setResource(Resource resource) { this.resource = resource; }

    public Compute getCompute() { return compute; }
    public void setCompute(Compute compute) { this.compute = compute; }

    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }

    public Network getNetwork() { return network; }
    public void setNetwork(Network network) { this.network = network; }

    public Disk getDisk() { return disk; }
    public void setDisk(Disk disk) { this.disk = disk; }

    public Database getDatabase() { return database; }
    public void setDatabase(Database database) { this.database = database; }

    public Application getApplication() { return application; }
    public void setApplication(Application application) { this.application = application; }
}
