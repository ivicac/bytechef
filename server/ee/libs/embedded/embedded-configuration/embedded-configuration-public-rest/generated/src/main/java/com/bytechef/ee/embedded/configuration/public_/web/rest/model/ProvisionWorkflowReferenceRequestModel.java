package com.bytechef.ee.embedded.configuration.public_.web.rest.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.HashMap;
import java.util.Map;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Connections the connected user chose, keyed by component name. Components not listed keep their current connection or are auto-matched.
 */

@Schema(name = "ProvisionWorkflowReferenceRequest", description = "Connections the connected user chose, keyed by component name. Components not listed keep their current connection or are auto-matched.")
@JsonTypeName("ProvisionWorkflowReferenceRequest")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-17T14:18:19.022624+02:00[Europe/Zagreb]", comments = "Generator version: 7.25.0")
public class ProvisionWorkflowReferenceRequestModel {

  private Map<String, Long> connections = new HashMap<>();

  public ProvisionWorkflowReferenceRequestModel connections(Map<String, Long> connections) {
    this.connections = connections;
    return this;
  }

  public ProvisionWorkflowReferenceRequestModel putConnectionsItem(String key, Long connectionsItem) {
    if (this.connections == null) {
      this.connections = new HashMap<>();
    }
    this.connections.put(key, connectionsItem);
    return this;
  }

  /**
   * Get connections
   * @return connections
   */
  
  @Schema(name = "connections", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("connections")
  public Map<String, Long> getConnections() {
    return connections;
  }

  @JsonProperty("connections")
  public void setConnections(Map<String, Long> connections) {
    this.connections = connections;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ProvisionWorkflowReferenceRequestModel provisionWorkflowReferenceRequest = (ProvisionWorkflowReferenceRequestModel) o;
    return Objects.equals(this.connections, provisionWorkflowReferenceRequest.connections);
  }

  @Override
  public int hashCode() {
    return Objects.hash(connections);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ProvisionWorkflowReferenceRequestModel {\n");
    sb.append("    connections: ").append(toIndentedString(connections)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(@Nullable Object o) {
    return o == null ? "null" : o.toString().replace("\n", "\n    ");
  }
}

