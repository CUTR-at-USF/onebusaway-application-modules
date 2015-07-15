/**
 * Copyright (C) 2011 Brian Ferris <bdferris@onebusaway.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.realtime.api;

import java.io.Serializable;

import org.onebusaway.gtfs.model.AgencyAndId;

public class TimepointPredictionRecord implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * 
   */
  private AgencyAndId timepointId;
  
  private AgencyAndId tripId;

  private long timepointScheduledTime;

  private Long timepointPredictedArrivalTime;

  private Long timepointPredictedDepartureTime;

  public TimepointPredictionRecord() {

  }

  public TimepointPredictionRecord(TimepointPredictionRecord r) {
    this.timepointId = r.timepointId;
    this.timepointScheduledTime = r.timepointScheduledTime;
    this.timepointPredictedArrivalTime = r.timepointPredictedArrivalTime;
    this.timepointPredictedDepartureTime = r.timepointPredictedDepartureTime;
    this.tripId = r.tripId;
  }

  public AgencyAndId getTimepointId() {
    return timepointId;
  }

  public void setTimepointId(AgencyAndId timepointId) {
    this.timepointId = timepointId;
  }

  public long getTimepointScheduledTime() {
    return timepointScheduledTime;
  }

  public void setTimepointScheduledTime(long timepointScheduledTime) {
    this.timepointScheduledTime = timepointScheduledTime;
  }

  public AgencyAndId getTripId() {
	  return tripId;
  }

  public void setTripId(AgencyAndId tripId) {
	  this.tripId = tripId;
  }

  public Long getTimepointPredictedArrivalTime() {
	  return timepointPredictedArrivalTime;
  }

  public void setTimepointPredictedArrivalTime(
		  Long timepointPredictedArrivalTime) {
	  this.timepointPredictedArrivalTime = timepointPredictedArrivalTime;
  }

  public Long getTimepointPredictedDepartureTime() {
	  return timepointPredictedDepartureTime;
  }

  public void setTimepointPredictedDepartureTime(
		  Long timepointPredictedDepartureTime) {
	  this.timepointPredictedDepartureTime = timepointPredictedDepartureTime;
  }
}
