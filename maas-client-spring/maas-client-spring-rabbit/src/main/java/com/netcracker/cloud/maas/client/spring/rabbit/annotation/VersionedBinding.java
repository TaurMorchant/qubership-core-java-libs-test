package com.netcracker.cloud.maas.client.spring.rabbit.annotation;

import lombok.Value;
import java.util.Map;

@Value
public class VersionedBinding {
    public String exchange;
    public String queue;
    public String routingKey;
    public Map<String, Object> arguments;
}

// GIB incremental-deploy E2E: trivial touch to trigger module rebuild

// GIB E2E #2: single-module change to verify minimal reactor after repo fix
