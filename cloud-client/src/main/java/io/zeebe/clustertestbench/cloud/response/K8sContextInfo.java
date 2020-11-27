package io.zeebe.clustertestbench.cloud.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class K8sContextInfo {

  private String uuid;
  private String name;
  private String region;
  private String zone;

  public String getUuid() {
    return uuid;
  }

  public void setUuid(final String uuid) {
    this.uuid = uuid;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(final String region) {
    this.region = region;
  }

  public String getZone() {
    return zone;
  }

  public void setZone(final String zone) {
    this.zone = zone;
  }

  @Override
  public String toString() {
    return "K8sContextInfo [uuid="
        + uuid
        + ", name="
        + name
        + ", region="
        + region
        + ", zone="
        + zone
        + "]";
  }
}
